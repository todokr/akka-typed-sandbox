import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

import Counter.Command
import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.util.Timeout

object CounterApp extends App {

  val system = ActorSystem(Guardian(), "system")

  implicit val timeout: Timeout = 3.seconds
  implicit val ec: ExecutionContext = system.executionContext
  implicit val scheduler: Scheduler = system.scheduler

  val counter: Future[ActorRef[Counter.Command]] =
    system.ask { replyTo =>
      SpawnProtocol.Spawn(
        behavior = Counter(),
        name = "counter",
        props = Props.empty,
        replyTo = replyTo
      )
    }

  for (counterRef <- counter) {
    counterRef ! Counter.Increment
    counterRef ! Counter.Increment
    counterRef ! Counter.Decrement
    counterRef
      .ask[Counter.CountResponse](Counter.ReadCount)
      .foreach(res => println(s"count: ${res.value}"))
  }

  system.terminate()
}

object Guardian {

  def apply(): Behavior[SpawnProtocol.Command] =
    Behaviors.setup(_ => SpawnProtocol())
}

class Counter(context: ActorContext[Command])
  extends AbstractBehavior[Command](context) {
  import Counter._

  private var _count = 0

  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case Increment =>
      _count += 1
      this

    case Decrement =>
      _count -= 1
      this

    case ReadCount(replyTo) =>
      replyTo ! CountResponse(_count)
      this
  }
}

object Counter {
  def apply(): Behavior[Command] =
    Behaviors.setup(context => new Counter(context))

  sealed trait Command
  case object Increment extends Command
  case object Decrement extends Command
  final case class ReadCount(replyTo: ActorRef[CountResponse]) extends Command

  final case class CountResponse(value: Int)
}

// Functional Style
//object Counter {
//
//  def apply(): Behavior[Command] = counter(0)
//
//  private def counter(count: Int): Behavior[Command] =
//    Behaviors.receive { (_, msg) =>
//      msg match {
//        case Increment =>
//          counter(count + 1)
//        case Decrement =>
//          counter(count - 1)
//        case ReadCount(replyTo) =>
//          replyTo ! CountResponse(count)
//          Behaviors.same
//      }
//    }
//}