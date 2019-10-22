import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import CountWorker.WorkerCommand
import akka.actor.{ActorSystem, Props}
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.util.Timeout
import akka.{actor => classic}

object Integrate extends App {
  val system = ActorSystem("count-app")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = 3.seconds

  val counter = system.actorOf(CountMaster.props, "master")

  counter ! CountMaster.Increment
  counter ! CountMaster.Increment
  counter ! CountMaster.Decrement
  counter ! CountMaster.PrintCount

  system.terminate()
}

class CountMaster extends classic.Actor with classic.ActorLogging {
  import CountMaster._
  import CountWorker.CountResponse

  implicit val timeout: Timeout = 3.seconds
  implicit val ec: ExecutionContext = context.system.dispatcher
  implicit val scheduler: Scheduler = context.system.scheduler.toTyped

  private val worker = context.system.spawn(CountWorker(), "count-worker")

  override def receive: Receive = {
    case Increment =>
      worker ! CountWorker.Plus(1)

    case Decrement =>
      worker ! CountWorker.Minus(1)

    case PrintCount =>
      worker
        .ask[CountResponse](replyTo => CountWorker.ReadCount(replyTo))
        .foreach(res => log.info("count: {}", res.value))
  }
}

object CountMaster {
  def props: Props = Props(new CountMaster)

  sealed trait MasterCommand
  case object Increment extends MasterCommand
  case object Decrement extends MasterCommand
  case object PrintCount extends MasterCommand
}

class CountWorker(context: ActorContext[WorkerCommand]) extends AbstractBehavior[WorkerCommand](context) {
  import CountWorker._

  private var _count = 0

  override def onMessage(msg: WorkerCommand): Behavior[WorkerCommand] = msg match {
    case Plus(n) =>
      _count = _count + n
      this

    case Minus(n) =>
      _count = _count - n
      this

    case ReadCount(replyTo) =>
      replyTo ! CountResponse(_count)
      this
  }
}

object CountWorker {
  def apply(): Behavior[WorkerCommand] =
    Behaviors.setup(context => new CountWorker(context))

  sealed trait WorkerCommand
  final case class Plus(n: Int) extends WorkerCommand
  final case class Minus(n: Int) extends WorkerCommand
  case class ReadCount(replyTo: ActorRef[CountResponse]) extends WorkerCommand

  case class CountResponse(value: Int)
}
