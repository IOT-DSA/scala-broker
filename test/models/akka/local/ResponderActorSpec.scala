package models.akka.local

import akka.testkit.TestProbe
import models.RequestEnvelope
import models.akka.{ AbstractActorSpec, ConnectionInfo, Messages }
import models.rpc.ListRequest

/**
 * ResponderActor test suite.
 */
class ResponderActorSpec extends AbstractActorSpec {

  val ci = ConnectionInfo("", "", false, true)
  val responder = system.actorOf(ResponderActor.props(ci), "responder")
  val ws = TestProbe()
  responder.tell(Messages.ConnectEndpoint(ws.ref, ci), ws.ref)

  "ResponderActor" should {
    "deliver requests to WS" in {
      val envelope = RequestEnvelope(List(ListRequest(1, "/")))
      responder.tell(envelope, testActor)
      ws.expectMsg(envelope)
    }
  }
}