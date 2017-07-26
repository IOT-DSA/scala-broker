package models.akka.local

import scala.concurrent.duration.DurationInt

import akka.actor.Props
import akka.testkit.TestProbe
import models.{ RequestEnvelope, ResponseEnvelope }
import models.akka.{ AbstractActorSpec, ConnectionInfo }
import models.rpc._
import models.rpc.DSAValue.{ StringValue, longToNumericValue, obj }

/**
 * PooledResponderBehavior test suite.
 */
class PooledResponderBehaviorSpec extends AbstractActorSpec {
  import models.rpc.StreamState._

  val ci = ConnectionInfo("", "", false, true)

  val responder = system.actorOf(Props(new DSLinkActor(ci) with PooledResponderBehavior {
    override def connected = super.connected orElse responderBehavior
  }), "R")

  val ws = TestProbe()

  responder.tell(DSLinkActor.ConnectEndpoint(ws.ref), ws.ref)

  val requesters = (1 to 5) map (_ -> TestProbe()) toMap

  "PooledResponderBehavior" should {
    "handle List requests" in {
      responder.tell(RequestEnvelope(List(ListRequest(101, "/downstream/R/blah"))), requesters(1).ref)
      ws.expectMsg(RequestEnvelope(List(ListRequest(1, "/blah"))))

      val rsp1 = DSAResponse(rid = 1, updates = Some(List(obj("a" -> "b"))))
      ws.reply(ResponseMessage(1, None, List(rsp1)))
      requesters(1).expectMsg(ResponseEnvelope(List(rsp1.copy(rid = 101))))

      responder.tell(RequestEnvelope(List(ListRequest(201, "/downstream/R/blah"))), requesters(2).ref)
      responder.tell(RequestEnvelope(List(ListRequest(301, "/downstream/R/blah"))), requesters(3).ref)
      requesters(2).expectMsg(ResponseEnvelope(List(rsp1.copy(rid = 201))))
      requesters(3).expectMsg(ResponseEnvelope(List(rsp1.copy(rid = 301))))

      val rsp2 = DSAResponse(rid = 1, updates = Some(List(obj("c" -> "d"))))
      ws.reply(ResponseMessage(1, None, List(rsp2)))

      requesters(1).expectMsg(ResponseEnvelope(List(rsp2.copy(rid = 101))))
      requesters(2).expectMsg(ResponseEnvelope(List(rsp2.copy(rid = 201))))
      requesters(3).expectMsg(ResponseEnvelope(List(rsp2.copy(rid = 301))))

      responder.tell(RequestEnvelope(List(CloseRequest(201))), requesters(2).ref)
      responder.tell(RequestEnvelope(List(CloseRequest(101))), requesters(1).ref)
      ws.expectNoMsg(1 second)

      responder.tell(RequestEnvelope(List(CloseRequest(301))), requesters(3).ref)
      ws.expectMsg(RequestEnvelope(List(CloseRequest(1))))

      responder.tell(RequestEnvelope(List(ListRequest(401, "/downstream/R/blah"))), requesters(4).ref)
      ws.expectMsg(RequestEnvelope(List(ListRequest(2, "/blah"))))

      responder.tell(RequestEnvelope(List(CloseRequest(401))), requesters(4).ref)
      ws.expectMsg(RequestEnvelope(List(CloseRequest(2))))
    }
    "handle Set requests" in {
      responder.tell(RequestEnvelope(List(SetRequest(111, "/downstream/R/blah", 5))), requesters(1).ref)
      responder.tell(RequestEnvelope(List(SetRequest(211, "/downstream/R/blah", 3))), requesters(2).ref)

      ws.receiveN(2).toSet mustBe Set(
        RequestEnvelope(List(SetRequest(3, "/blah", 5))),
        RequestEnvelope(List(SetRequest(4, "/blah", 3))))

      ws.reply(ResponseMessage(1, None, List(DSAResponse(3, Some(Closed)), DSAResponse(4, Some(Closed)))))

      requesters(1).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 111, stream = Some(Closed)))))
      requesters(2).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 211, stream = Some(Closed)))))
    }
    "handle Remove requests" in {
      responder.tell(RequestEnvelope(List(RemoveRequest(121, "/downstream/R/blah"))), requesters(1).ref)
      responder.tell(RequestEnvelope(List(RemoveRequest(321, "/downstream/R/blah"))), requesters(3).ref)

      ws.receiveN(2).toSet mustBe Set(
        RequestEnvelope(List(RemoveRequest(5, "/blah"))),
        RequestEnvelope(List(RemoveRequest(6, "/blah"))))

      ws.reply(ResponseMessage(1, None, List(DSAResponse(5, Some(Closed)), DSAResponse(6, Some(Closed)))))

      requesters(1).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 121, stream = Some(Closed)))))
      requesters(3).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 321, stream = Some(Closed)))))
    }
    "handle non-streaming Invoke requests" in {
      responder.tell(RequestEnvelope(List(InvokeRequest(131, "/downstream/R/blah"))), requesters(1).ref)
      responder.tell(RequestEnvelope(List(InvokeRequest(231, "/downstream/R/blah"))), requesters(2).ref)

      ws.receiveN(2).toSet mustBe Set(
        RequestEnvelope(List(InvokeRequest(7, "/blah"))),
        RequestEnvelope(List(InvokeRequest(8, "/blah"))))

      ws.reply(ResponseMessage(1, None, List(DSAResponse(7, Some(Closed)), DSAResponse(8, Some(Closed)))))

      requesters(1).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 131, stream = Some(Closed)))))
      requesters(2).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 231, stream = Some(Closed)))))
    }
    "handle streaming Invoke requests" in {
      responder.tell(RequestEnvelope(List(InvokeRequest(141, "/downstream/R/blah"))), requesters(1).ref)
      responder.tell(RequestEnvelope(List(InvokeRequest(241, "/downstream/R/blah"))), requesters(2).ref)

      ws.receiveN(2).toSet mustBe Set(
        RequestEnvelope(List(InvokeRequest(9, "/blah"))),
        RequestEnvelope(List(InvokeRequest(10, "/blah"))))

      ws.reply(ResponseMessage(1, None, List(DSAResponse(9), DSAResponse(10))))

      requesters(1).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 141))))
      requesters(2).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 241))))

      ws.reply(ResponseMessage(1, None, List(DSAResponse(9))))

      requesters(1).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 141))))

      ws.reply(ResponseMessage(1, None, List(DSAResponse(10, stream = Some(Closed)))))

      requesters(2).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 241, stream = Some(Closed)))))

      ws.reply(ResponseMessage(1, None, List(DSAResponse(9, stream = Some(Closed)))))

      requesters(1).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 141, stream = Some(Closed)))))
    }
    "handle Subscribe requests" in {
      responder.tell(RequestEnvelope(List(SubscribeRequest(151, List(
        SubscriptionPath("/downstream/R/blahA", 1001))))), requesters(1).ref)

      ws.expectMsg(RequestEnvelope(List(SubscribeRequest(11, List(
        SubscriptionPath("/blahA", 1))))))

      responder.tell(RequestEnvelope(List(SubscribeRequest(251, List(
        SubscriptionPath("/downstream/R/blahB", 2001))))), requesters(2).ref)

      ws.expectMsg(RequestEnvelope(List(SubscribeRequest(12, List(
        SubscriptionPath("/blahB", 2))))))

      ws.reply(ResponseMessage(1, None, List(DSAResponse(rid = 11, stream = Some(Closed)))))
      val rsp1 = DSAResponse(rid = 0, updates = Some(List(obj("sid" -> 1, "data" -> 111))))
      ws.reply(ResponseMessage(1, None, List(rsp1)))

      requesters(1).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 151, stream = Some(Closed)))))
      requesters(1).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 0,
        updates = Some(List(obj("sid" -> 1001, "data" -> 111)))))))

      ws.reply(ResponseMessage(1, None, List(DSAResponse(rid = 12, stream = Some(Closed)))))
      val rsp2 = DSAResponse(rid = 0, updates = Some(List(obj("sid" -> 2, "data" -> 222))))
      ws.reply(ResponseMessage(1, None, List(rsp2)))

      requesters(2).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 251, stream = Some(Closed)))))
      requesters(2).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 0,
        updates = Some(List(obj("sid" -> 2001, "data" -> 222)))))))

      responder.tell(RequestEnvelope(List(SubscribeRequest(351, List(
        SubscriptionPath("/downstream/R/blahA", 3001))))), requesters(3).ref)

      requesters(3).receiveN(2).toSet mustBe Set(
        ResponseEnvelope(List(DSAResponse(rid = 351, stream = Some(Closed)))),
        ResponseEnvelope(List(DSAResponse(rid = 0, updates = Some(List(obj("sid" -> 3001, "data" -> 111)))))))

      val rsp2a = DSAResponse(rid = 0, updates = Some(List(obj("sid" -> 2, "data" -> 2222))))
      ws.reply(ResponseMessage(1, None, List(rsp2a)))

      requesters(2).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 0,
        updates = Some(List(obj("sid" -> 2001, "data" -> 2222)))))))

      responder.tell(RequestEnvelope(List(UnsubscribeRequest(152, List(1001)))), requesters(1).ref)
      ws.expectNoMsg()
      requesters(1).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 152, stream = Some(Closed)))))

      responder.tell(RequestEnvelope(List(UnsubscribeRequest(352, List(3001)))), requesters(3).ref)
      ws.expectMsg(RequestEnvelope(List(UnsubscribeRequest(rid = 13, List(1)))))
      ws.reply(ResponseMessage(1, None, List(DSAResponse(rid = 13, stream = Some(Closed)))))
      requesters(3).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 352, stream = Some(Closed)))))

      responder.tell(RequestEnvelope(List(UnsubscribeRequest(252, List(2001)))), requesters(2).ref)
      ws.expectMsg(RequestEnvelope(List(UnsubscribeRequest(rid = 14, List(2)))))
      ws.reply(ResponseMessage(1, None, List(DSAResponse(rid = 14, stream = Some(Closed)))))
      requesters(2).expectMsg(ResponseEnvelope(List(DSAResponse(rid = 252, stream = Some(Closed)))))
    }
  }
}