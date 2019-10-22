import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.{Matchers, WordSpecLike}
import scala.concurrent.duration._

class IoT extends ScalaTestWithActorTestKit with WordSpecLike with Matchers {
  import Device._

  "Device actor" must {
    "reply with empty reading if no temperature is known" in {
      val probe = createTestProbe[RespondTemperature]()
      val deviceActor = spawn(Device("group", "device"))

      deviceActor ! Device.ReadTemperature(requestId = 42, probe.ref)

      val response = probe.receiveMessage()
      response.requestId shouldBe 42
      response.value shouldBe None
    }

    "reply with latest temperature reading" in {
      val recordProbe = createTestProbe[TemperatureRecorded]()
      val readProbe = createTestProbe[RespondTemperature]()
      val deviceActor = spawn(Device("group", "device"))

      deviceActor ! Device.RecordTemperature(requestId = 1, 24.0, recordProbe.ref)
      recordProbe expectMessage Device.TemperatureRecorded(requestId = 1)

      deviceActor ! Device.ReadTemperature(requestId = 2, readProbe.ref)
      readProbe expectMessage Device.RespondTemperature(requestId = 2, "device", Some(24.0))

      deviceActor ! Device.RecordTemperature(requestId = 3, 55.0, recordProbe.ref)
      recordProbe expectMessage Device.TemperatureRecorded(requestId = 3)

      deviceActor ! Device.ReadTemperature(requestId = 4, readProbe.ref)
      readProbe expectMessage Device.RespondTemperature(requestId = 4, "device", Some(55.0))
    }

    "be able to register a device actor" in {
      val probe = createTestProbe[DeviceManager.DeviceRegistered]()
      val groupActor = spawn(DeviceGroup("group"))

      groupActor ! DeviceManager.RequestTrackDevice("group", "device1", probe.ref)
      val registered1 = probe.receiveMessage()
      val deviceActor1 = registered1.device

      groupActor ! DeviceManager.RequestTrackDevice("group", "device2", probe.ref)
      val registered2 = probe.receiveMessage()
      val deviceActor2 = registered2.device

      val recordProbe = createTestProbe[TemperatureRecorded]()

      deviceActor1 ! RecordTemperature(requestId = 0, 1.0, recordProbe.ref)
      recordProbe expectMessage TemperatureRecorded(requestId = 0)

      deviceActor2 ! RecordTemperature(requestId = 1, 2.0, recordProbe.ref)
      recordProbe expectMessage TemperatureRecorded(requestId = 1)
    }

    "ignore request for wrong groupId" in {
      val probe = createTestProbe[DeviceManager.DeviceRegistered]()
      val groupActor = spawn(DeviceGroup("group"))

      groupActor ! DeviceManager.RequestTrackDevice("wrongGroup", "device1", probe.ref)
      probe.expectNoMessage(500.milliseconds)
    }

    "return same actor for same deviceId" in {
      val probe = createTestProbe[DeviceManager.DeviceRegistered]()
      val groupActor = spawn(DeviceGroup("group"))

      groupActor ! DeviceManager.RequestTrackDevice("group", "device1", probe.ref)
      val registered1 = probe.receiveMessage()

      groupActor ! DeviceManager.RequestTrackDevice("group", "device1", probe.ref)
      val registered2 = probe.receiveMessage()

      registered1.device shouldBe registered2.device
    }

    "be able to list active devices" in {
      val registeredProbe = createTestProbe[DeviceManager.DeviceRegistered]()
      val groupActor = spawn(DeviceGroup("group"))

      groupActor ! DeviceManager.RequestTrackDevice("group", "device1", registeredProbe.ref)
      registeredProbe.receiveMessage()

      groupActor ! DeviceManager.RequestTrackDevice("group", "device2", registeredProbe.ref)
      registeredProbe.receiveMessage()

      val deviceListProbe = createTestProbe[DeviceManager.ReplyDeviceList]()
      groupActor ! DeviceManager.RequestDeviceList(requestId = 0, groupId = "group", deviceListProbe.ref)
      deviceListProbe.expectMessage(DeviceManager.ReplyDeviceList(requestId = 0, Set("device1", "device2")))
    }

    "be able to list active devices after one shuts down" in {
      val registeredProbe = createTestProbe[DeviceManager.DeviceRegistered]()
      val groupActor = spawn(DeviceGroup("group"))

      groupActor ! DeviceManager.RequestTrackDevice("group", "device1", registeredProbe.ref)
      val registered1 = registeredProbe.receiveMessage()
      val toShutDown = registered1.device

      groupActor ! DeviceManager.RequestTrackDevice("group", "device2", registeredProbe.ref)
      registeredProbe.receiveMessage()

      val deviceListProbe = createTestProbe[DeviceManager.ReplyDeviceList]()
      groupActor ! DeviceManager.RequestDeviceList(requestId = 0, groupId = "group", deviceListProbe.ref)
      deviceListProbe.expectMessage(DeviceManager.ReplyDeviceList(requestId = 0, Set("device1", "device2")))

      toShutDown ! Passivate
      registeredProbe.expectTerminated(toShutDown, registeredProbe.remainingOrDefault)

      // using awaitAssert to retry because it might take longer for the groupActor
      // to see the Terminated, that order is undefined
      registeredProbe.awaitAssert {
        groupActor ! DeviceManager.RequestDeviceList(requestId = 1, groupId = "group", deviceListProbe.ref)
        deviceListProbe.expectMessage(DeviceManager.ReplyDeviceList(requestId = 1, Set("device2")))
      }
    }
  }
}
