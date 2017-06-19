package models.akka

import akka.testkit.TestProbe
import models.{ RequestEnvelope, ResponseEnvelope }
import models.rpc._

/**
 * WSActor test suite.
 */
class WSActorSpec extends AbstractActorSpec {

  val salt = 1234
  val config = WSActorConfig("ws", salt)
  val link = TestProbe()
  val wsActor = system.actorOf(WSActor.props(testActor, link.ref, config))

  "WSActor" should {
    "send 'allowed' to socket and 'connected' to link on startup" in {
      expectMsg(AllowedMessage(true, salt))
      link.expectMsg(DSLinkActor.WSConnected)
    }
    "return ack for a ping message" in {
      wsActor ! PingMessage(101)
      expectMsg(PingMessage(1, Some(101)))
      wsActor ! PingMessage(102)
      expectMsg(PingMessage(2, Some(102)))
    }
    "forward request message to link and return ack to socket" in {
      val msg = RequestMessage(104, None, List(ListRequest(111, "/path")))
      wsActor ! msg
      expectMsg(PingMessage(3, Some(104)))
      link.expectMsg(msg)
    }
    "forward response message to link and return ack to socket" in {
      val msg = ResponseMessage(105, None, List(DSAResponse(111)))
      wsActor ! msg
      expectMsg(PingMessage(4, Some(105)))
      link.expectMsg(msg)
    }
    "send enveloped requests to socket" in {
      val req = ListRequest(111, "/path")
      wsActor ! RequestEnvelope("from", "to", List(req))
      expectMsg(RequestMessage(5, None, List(req)))
    }
    "send enveloped responses to socket" in {
      val rsp = DSAResponse(111)
      wsActor ! ResponseEnvelope("from", "to", List(rsp))
      expectMsg(ResponseMessage(6, None, List(rsp)))
    }
  }
}