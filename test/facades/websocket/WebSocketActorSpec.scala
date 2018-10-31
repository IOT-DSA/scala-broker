package facades.websocket

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.{Actor, Props}
import akka.actor.Status.Success
import akka.routing.ActorRefRoutee
import akka.stream.{ActorMaterializer, OverflowStrategy}

import scala.concurrent.duration._
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, StreamRefs}
import akka.testkit.TestProbe
import models.{RequestEnvelope, ResponseEnvelope}
import models.akka.{AbstractActorSpec, ConnectionInfo}
import models.rpc._

import scala.concurrent.Await
import akka.pattern.ask

/**
 * WebSocketActor test suite.
 */
class WebSocketActorSpec extends AbstractActorSpec {
  import models.akka.Messages._

  implicit val mat = ActorMaterializer()

  val salt = 1234
  val ci = ConnectionInfo("", "ws", true, false)
  val config = WebSocketActorConfig(ci, "session", salt)
  val link = TestProbe()
  implicit val timeout = akka.util.Timeout(2, TimeUnit.SECONDS)

  val futureSink = StreamRefs.sinkRef[DSAMessage]()
    .toMat(Sink.actorRef(testActor, Success(())))(Keep.left).run()

  val sink = Await.result(futureSink, 1 second)

  val actor = new ActorRefRoutee(link.ref)

  val registry = system.actorOf(Props(new Actor(){
    override def receive: Receive = {
      case _ => sender ! actor
    }
  }))

  val wsActor = system.actorOf(WebSocketActor.props(registry, config))
  val flow = Await.result((wsActor ? StreamRequest()).mapTo[Flow[DSAMessage, DSAMessage, NotUsed]], 5 second)

  val queue = flow
    .toMat(Sink.actorRef(self, Success(())))(Keep.right)
    .runWith(Source.queue(10, OverflowStrategy.backpressure))

  "WSActor" should {
    "send 'allowed' to socket and 'connected' to link on startup" in {
      expectMsg(AllowedMessage(true, salt))
      link.expectMsg(ConnectEndpoint(ci))
    }
    "return ack for a ping message" in {
      wsActor ! PingMessage(101)
      expectMsg(PongMessage(101))
      wsActor ! PingMessage(102)
      expectMsg(PongMessage(102))
    }
    "forward request message to link and return ack to socket" in {
      val msg = RequestMessage(104, None, List(ListRequest(111, "/path")))
      wsActor ! msg
      expectMsg(PongMessage(104))
      link.expectMsg(msg)
    }
    "forward response message to link and return ack to socket" in {
      val msg = ResponseMessage(105, None, List(DSAResponse(111)))
      wsActor ! msg
      expectMsg(PongMessage(105))
      link.expectMsg(msg)
    }
    "send enveloped requests to socket" in {
      val req = ListRequest(111, "/path")
      wsActor ! RequestEnvelope(List(req))
      expectMsg(RequestMessage(1, None, List(req)))
    }
    "send enveloped responses to socket" in {
      val rsp = DSAResponse(111)
      wsActor ! ResponseEnvelope(List(rsp))
      expectMsg(ResponseMessage(2, None, List(rsp)))
    }
  }
}