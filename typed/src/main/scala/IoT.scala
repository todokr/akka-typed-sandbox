
import scala.concurrent.duration.FiniteDuration

import DeviceManager.DeviceRegistered
import akka.actor.typed._
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, TimerScheduler}

class IoTSupervisor(context: ActorContext[Nothing]) extends AbstractBehavior[Nothing](context) {
  context.log.info("IoT Application started")

  override def onMessage(msg: Nothing): Behavior[Nothing] =
    Behaviors.unhandled

  override def onSignal: PartialFunction[Signal, Behavior[Nothing]] = {
    case PostStop =>
      context.log.info("IoT Application stopped")
      this
  }
}

object IoTSupervisor {
  def apply(): Behavior[Nothing] =
    Behaviors.setup[Nothing](context => new IoTSupervisor(context))
}

class Device(
  context: ActorContext[Device.Command],
  groupId: String,
  deviceId: String
) extends AbstractBehavior[Device.Command](context) {
  import Device._

  var lastTemperatureReading: Option[Double] = None

  context.log.info("Device actor {}-{} started", groupId, deviceId)

  override def onMessage(msg: Device.Command): Behavior[Device.Command] = msg match {
    case RecordTemperature(id, value, replyTo) =>
      context.log.info("Recorded temperature reading {} with {}", value, id)
      lastTemperatureReading = Some(value)
      replyTo ! TemperatureRecorded(id)
      this

    case ReadTemperature(id, replyTo) =>
      replyTo ! RespondTemperature(id, deviceId, lastTemperatureReading)
      this

    case Passivate =>
      Behaviors.stopped
  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case PostStop =>
    context.log.info("Device actor {}-{} stopped", groupId, deviceId)
    this
  }
}

object Device {

  def apply(groupId: String, deviceId: String): Behavior[Command] =
    Behaviors.setup(context => new Device(context, groupId, deviceId))

  sealed trait Command

  final case class ReadTemperature(requestId: Long, replyTo: ActorRef[RespondTemperature]) extends Command
  final case class RespondTemperature(requestId: Long, deviceId: String, value: Option[Double])

  final case class RecordTemperature(requestId: Long, value: Double, replyTo: ActorRef[TemperatureRecorded]) extends Command
  final case class TemperatureRecorded(requestId: Long)

  case object Passivate extends Command
}

class DeviceManager(context: ActorContext[DeviceManager.Command]) extends AbstractBehavior[DeviceManager.Command](context) {

  import DeviceManager._

  private var groupIdToActor = Map.empty[String, ActorRef[DeviceGroup.Command]]

  context.log.info("DeviceManager started")

  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case trackMsg@RequestTrackDevice(groupId, _, _) => groupIdToActor.get(groupId) match {
      case Some(ref) => ref ! trackMsg
      case None => context.log.info("Creating device group actor for {}", groupId)
        val groupActor = context.spawn(DeviceGroup(groupId), "group-" + groupId)
        context.watchWith(groupActor, DeviceGroupTerminated(groupId))
        groupActor ! trackMsg
        groupIdToActor += groupId -> groupActor
    }
      this
    case req@RequestDeviceList(requestId, groupId, replyTo) => groupIdToActor.get(groupId) match {
      case Some(ref) => ref ! req
      case None => replyTo ! ReplyDeviceList(requestId, Set.empty)
    }
      this
    case DeviceGroupTerminated(groupId) => context.log.info("Device group actor for {} has been terminated", groupId)
      groupIdToActor -= groupId
      this
  }
}

object DeviceManager {

  trait Command

  case class RequestTrackDevice(groupId: String, deviceId: String, replyTo: ActorRef[DeviceRegistered])
    extends DeviceManager.Command with DeviceGroup.Command
  final case class DeviceRegistered(device: ActorRef[Device.Command])

  final case class RequestDeviceList(requestId: Long, groupId: String, replyTo: ActorRef[ReplyDeviceList])
    extends DeviceManager.Command with DeviceGroup.Command
  final case class ReplyDeviceList(requestId: Long, ids: Set[String])

  private final case class DeviceGroupTerminated(groupId: String) extends DeviceManager.Command

  final case class RequestAllTemperatures(requestId: Long, groupId: String, replyTo: ActorRef[RespondAllTemperatures])
    extends DeviceGroupQuery.Command
      with DeviceGroup.Command
      with DeviceManager.Command

  final case class RespondAllTemperatures(requestId: Long, temperatures: Map[String, TemperatureReading])

  sealed trait TemperatureReading
  final case class Temperature(value: Double) extends TemperatureReading
  case object TemperatureNotAvailable extends TemperatureReading
  case object DeviceNotAvailable extends TemperatureReading
  case object DeviceTimedOut extends TemperatureReading
}

class DeviceGroup(context: ActorContext[DeviceGroup.Command], groupId: String) extends AbstractBehavior[DeviceGroup.Command](context) {
  import DeviceGroup._
  import DeviceManager.{ReplyDeviceList, RequestDeviceList, RequestTrackDevice}

  private var deviceIdToActor = Map.empty[String, ActorRef[Device.Command]]

  context.log.info("DeviceGroup {} started", groupId)

  override def onMessage(msg: Command): Behavior[DeviceGroup.Command] = msg match {
    case trackMsg @ RequestTrackDevice(`groupId`, deviceId, replyTo) => deviceIdToActor.get(deviceId) match {
      case Some(deviceActor) =>
        replyTo ! DeviceRegistered(deviceActor)
        this
      case None =>
        context.log.info("Creating device actor for {}", trackMsg.deviceId)
        val deviceActor = context.spawn(Device(groupId, deviceId), s"device-$deviceId")
        context.watchWith(deviceActor, DeviceTerminated(deviceActor, groupId, deviceId))
        deviceIdToActor += deviceId -> deviceActor
        replyTo ! DeviceRegistered(deviceActor)
        this
    }

    case RequestTrackDevice(gId, _, _) =>
      context.log.warn("Ignoring TrackDevice request for {}. This actor is responsible for {}.", gId, groupId)
      this

    case RequestDeviceList(requestId, gId, replyTo) =>
      if (gId == groupId) {
        replyTo ! ReplyDeviceList(requestId, deviceIdToActor.keySet)
        this
      } else Behaviors.unhandled

    case DeviceTerminated(_, _, deviceId) =>
      context.log.info("Device actor for {} has been terminated", deviceId)
      deviceIdToActor -= deviceId
      this
  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case PostStop =>
      context.log.info("DeviceGroup {}, stopped", groupId)
      this
  }
}

object DeviceGroup {

  def apply(groupId: String): Behavior[Command] =
    Behaviors.setup(context => new DeviceGroup(context, groupId))

  trait Command

  private final case class DeviceTerminated(device: ActorRef[Device.Command], groupId: String, deviceId: String) extends Command
}

class DeviceGroupQuery(
  deviceIdToActor: Map[String, ActorRef[Device.Command]],
  requestId: Long,
  requester: ActorRef[DeviceManager.RespondAllTemperatures],
  timeout: FiniteDuration,
  context: ActorContext[DeviceGroupQuery.Command],
  timers: TimerScheduler[DeviceGroupQuery.Command])
  extends AbstractBehavior[DeviceGroupQuery.Command](context) {

  import DeviceGroupQuery._
  import DeviceManager.{Command => _, _}

  timers.startSingleTimer(CollectionTimeout, CollectionTimeout, timeout)

  private val respondTemperatureAdapter = context.messageAdapter(WrappedRespondTemperature.apply)

  private var repliesSoFar = Map.empty[String, TemperatureReading]
  private var stillWaiting = deviceIdToActor.keySet


  deviceIdToActor.foreach {
    case (deviceId, device) =>
      context.watchWith(device, DeviceTerminated(deviceId))
      device ! Device.ReadTemperature(0, respondTemperatureAdapter)
  }

  override def onMessage(msg: Command): Behavior[Command] =
    msg match {
      case WrappedRespondTemperature(response) => onRespondTemperature(response)
      case DeviceTerminated(deviceId)          => onDeviceTerminated(deviceId)
      case CollectionTimeout                   => onCollectionTimout()
    }

  private def onRespondTemperature(response: Device.RespondTemperature): Behavior[Command] = {
    val reading = response.value match {
      case Some(value) => Temperature(value)
      case None        => TemperatureNotAvailable
    }

    val deviceId = response.deviceId
    repliesSoFar += (deviceId -> reading)
    stillWaiting -= deviceId

    respondWhenAllCollected()
  }

  private def onDeviceTerminated(deviceId: String): Behavior[Command] = {
    if (stillWaiting(deviceId)) {
      repliesSoFar += (deviceId -> DeviceNotAvailable)
      stillWaiting -= deviceId
    }
    respondWhenAllCollected()
  }

  private def onCollectionTimout(): Behavior[Command] = {
    repliesSoFar ++= stillWaiting.map(deviceId => deviceId -> DeviceTimedOut)
    stillWaiting = Set.empty
    respondWhenAllCollected()
  }

  private def respondWhenAllCollected(): Behavior[Command] = {
    if (stillWaiting.isEmpty) {
      requester ! RespondAllTemperatures(requestId, repliesSoFar)
      Behaviors.stopped
    } else {
      this
    }
  }
}

object DeviceGroupQuery {

  def apply(
    deviceIdToActor: Map[String, ActorRef[Device.Command]],
    requestId: Long,
    requester: ActorRef[DeviceManager.RespondAllTemperatures],
    timeout: FiniteDuration): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new DeviceGroupQuery(deviceIdToActor, requestId, requester, timeout, context, timers)
      }
    }
  }

  trait Command

  private case object CollectionTimeout extends Command

  final case class WrappedRespondTemperature(response: Device.RespondTemperature) extends Command

  private final case class DeviceTerminated(deviceId: String) extends Command
}