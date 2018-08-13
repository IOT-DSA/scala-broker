package facades.websocket

import akka.routing.ActorRefRoutee
import akka.testkit.TestProbe
import models.{ OutRequestEnvelope, ResponseEnvelope }
import models.akka.{ AbstractActorSpec, ConnectionInfo }
import models.rpc._

/**
 * WebSocketActor test suite.
 */
class WebSocketActorSpec extends AbstractActorSpec {
  import models.akka.Messages._

  val salt = 1234
  val ci = ConnectionInfo("", "ws", true, false)
  val config = WebSocketActorConfig(ci, "session", salt)
  val link = TestProbe()
  val wsActor = system.actorOf(WebSocketActor.props(testActor, new ActorRefRoutee(link.ref), config))

  "WSActor" should {
    "send 'allowed' to socket and 'connected' to link on startup" in {
      expectMsg(AllowedMessage(true, salt))
      link.expectMsg(ConnectEndpoint(wsActor, ci))
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
      wsActor ! OutRequestEnvelope(List(req))
      expectMsg(RequestMessage(1, None, List(req)))
    }
    "send enveloped responses to socket" in {
      val rsp = DSAResponse(111)
      wsActor ! ResponseEnvelope(List(rsp))
      expectMsg(ResponseMessage(2, None, List(rsp)))
    }
  }
}