package models.akka

import akka.actor.{ Actor, Props, actorRef2Scala }
import akka.testkit.TestProbe
import models.{ RequestEnvelope, ResponseEnvelope }
import models.akka.Messages.{ DSLinkStateChanged, RegisterDSLink }
import models.metrics.EventDaos
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
  val downstreamActor = system.actorOf(Props(new DownstreamActor), "downstream")

  val brokerProbe = TestProbe()
  class BrokerActor extends Actor {
    def receive = { case msg => brokerProbe.ref ! msg }
  }
  val brokerActor = system.actorOf(Props(new BrokerActor), "broker")

  val requester = system.actorOf(Props(new Requester(nullDaos)), "requester")
  val ws = TestProbe()

  "RequesterActor" should {
    "register with downstream" in {
      downstreamProbe.expectMsg(RegisterDSLink("requester", DSLinkMode.Requester, false))
    }
    "notify downstream on connection" in {
      val ci = ConnectionInfo("", "", true, false)
      requester.tell(Messages.ConnectEndpoint(ws.ref, ci), ws.ref)
      downstreamProbe.expectMsg(DSLinkStateChanged("requester", DSLinkMode.Requester, true))
    }
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
  class Requester(eventDaos: EventDaos) extends AbstractDSLinkActor(eventDaos) with RequesterBehavior {
    override def connected = super.connected orElse requesterBehavior
  }
}