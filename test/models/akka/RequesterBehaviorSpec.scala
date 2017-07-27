package models.akka

import akka.actor.{ Actor, Props, actorRef2Scala }
import akka.testkit.TestProbe
import models.{ RequestEnvelope, ResponseEnvelope, Settings }
import models.rpc.{ DSAResponse, ListRequest, RequestMessage }

/**
 * RequesterBehavior test suite.
 */
class RequesterBehaviorSpec extends AbstractActorSpec {
  import RequesterBehaviorSpec._

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
  val requester = system.actorOf(Props(new Requester), "requester")
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

/**
 * Common definitions for [[RequesterBehaviorSpec]].
 */
object RequesterBehaviorSpec {
  /**
   * Test actor.
   */
  class Requester extends AbstractDSLinkActor with RequesterBehavior {
    
    override def connected = super.connected orElse requesterBehavior
    
    def dsaSend(to: String, msg: Any) = context.actorSelection("/user/" + Settings.Nodes.Root + to) ! msg
  }
}