import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout

object CounterApp extends App {
  val system = ActorSystem("count-app")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = 3.seconds

  val counter = system.actorOf(Counter.props, "counter")

  counter ! Counter.Increment
  counter ! Counter.Increment
  counter ! Counter.Decrement

  counter.ask(Counter.ReadCount)
    .mapTo[Counter.CountResponse] // Future[Any]をFuture[Counter.CountResponse] に変換
    .foreach(res => println(s"count: ${res.value}"))

  system.terminate()
}

class Counter extends Actor with ActorLogging {
  import Counter._

  private var _count = 0

  override def receive: Receive = {
    case Increment =>
      _count += 1

    case ReadCount =>
      sender() ! CountResponse(_count)
  }
}

object Counter {
  def props: Props = Props(new Counter)

  sealed trait Command
  case object Increment extends Command
  case object Decrement extends Command
  case object ReadCount extends Command

  final case class CountResponse(value: Int)
}
