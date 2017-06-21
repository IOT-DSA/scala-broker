package models.akka

import scala.concurrent.duration.DurationInt

import com.typesafe.config.ConfigFactory

import akka.actor.{ Actor, Props }
import akka.testkit.TestProbe
import akka.util.Timeout
import models.{ RequestEnvelope, ResponseEnvelope, Settings }
import models.rpc.{ DSAResponse, ListRequest, RequestMessage }
import play.api.Configuration

/**
 * RequesterActor test suite.
 */
class RequesterActorSpec extends AbstractActorSpec {

  val settings = new Settings(new Configuration(ConfigFactory.load))

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

  val requester = system.actorOf(RequesterActor.props(settings), "requester")
  val ws = TestProbe()
  requester.tell(DSLinkActor.WSConnected, ws.ref)

  "RequesterActor" should {
    "route requests to broker root" in {
      val requests = List(ListRequest(1, "/"))
      requester.tell(RequestMessage(1, None, requests), ws.ref)
      brokerProbe.expectMsg(RequestEnvelope("/downstream/requester", "/", requests))
    }
    "route requests to downstream" in {
      val requests = List(ListRequest(1, "/downstream"))
      requester.tell(RequestMessage(1, None, requests), ws.ref)
      downstreamProbe.expectMsg(RequestEnvelope("/downstream/requester", "/downstream", requests))
    }
    "route requests to links" in {
      val requests = List(ListRequest(1, "/downstream/abc"))
      requester.tell(RequestMessage(1, None, requests), ws.ref)
      abcProbe.expectMsg(RequestEnvelope("/downstream/requester", "/downstream/abc", requests))
    }
    "forward responses to WS" in {
      val envelope = ResponseEnvelope("source", "target", List(DSAResponse(1)))
      requester.tell(envelope, testActor)
      ws.expectMsg(envelope)
    }
  }
}