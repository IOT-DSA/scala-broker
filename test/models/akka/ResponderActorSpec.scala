package models.akka

import com.typesafe.config.ConfigFactory

import akka.testkit.TestProbe
import models.{ RequestEnvelope, Settings }
import models.rpc.ListRequest
import play.api.Configuration

/**
 * ResponderActor test suite.
 */
class ResponderActorSpec extends AbstractActorSpec {

  val settings = new Settings(new Configuration(ConfigFactory.load))

  val responder = system.actorOf(ResponderActor.props(settings), "responder")
  val ws = TestProbe()
  responder.tell(DSLinkActor.WSConnected, ws.ref)

  "ResponderActor" should {
    "deliver requests to WS" in {
      val envelope = RequestEnvelope("source", "target", List(ListRequest(1, "/")))
      responder.tell(envelope, testActor)
      ws.expectMsg(envelope)
    }
  }
}