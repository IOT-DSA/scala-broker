package models.akka.local

import akka.actor.{ Actor, Props, actorRef2Scala }
import akka.testkit.TestProbe
import models.{ RequestEnvelope, ResponseEnvelope }
import models.akka.{ AbstractActorSpec, ConnectionInfo, Messages }
import models.rpc.{ DSAResponse, ListRequest, RequestMessage }

/**
 * RequesterActor test suite.
 */
class RequesterActorSpec extends AbstractActorSpec {

  val abcProbe = TestProbe()
  class AbcActor extends Actor {
    def receive = { case msg => abcProbe.ref ! msg }
  }

  val downstreamProbe = TestProbe()
  class DownstreamActor extends Actor {
    val abc = context.actorOf(Props(new AbcActor), "abc")
    def receive = { case msg => downstreamProbe.ref ! msg }
  }

  val brokerProbe = TestProbe()
  val brokerActor = system.actorOf(Props(new Actor {
    val downstream = context.actorOf(Props(new DownstreamActor), "downstream")
    def receive = { case msg => brokerProbe.ref ! msg }
  }), "broker")

  val ci = ConnectionInfo("", "", true, false)
  val requester = system.actorOf(RequesterActor.props(ci), "requester")
  val ws = TestProbe()
  requester.tell(Messages.ConnectEndpoint(ws.ref, ci), ws.ref)

  "RequesterActor" should {
    "route requests to broker root" in {
      val requests = List(ListRequest(1, "/"))
      requester.tell(RequestMessage(1, None, requests), ws.ref)
      brokerProbe.expectMsg(RequestEnvelope(requests))
    }
    "route requests to downstream" in {
      val requests = List(ListRequest(1, "/downstream"))
      requester.tell(RequestMessage(1, None, requests), ws.ref)
      downstreamProbe.expectMsg(RequestEnvelope(requests))
    }
    "route requests to links" in {
      val requests = List(ListRequest(1, "/downstream/abc"))
      requester.tell(RequestMessage(1, None, requests), ws.ref)
      abcProbe.expectMsg(RequestEnvelope(requests))
    }
    "forward responses to WS" in {
      val envelope = ResponseEnvelope(List(DSAResponse(1)))
      requester.tell(envelope, testActor)
      ws.expectMsg(envelope)
    }
  }
}