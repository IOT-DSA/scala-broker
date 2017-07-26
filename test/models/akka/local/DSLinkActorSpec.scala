package models.akka.local

import akka.actor.{ Props, actorRef2Scala }
import akka.testkit.TestProbe
import models.akka.{ AbstractActorSpec, ConnectionInfo }

/**
 * DSLinkActor test suite.
 */
class DSLinkActorSpec extends AbstractActorSpec {

  val ci = ConnectionInfo("", "", true, false)

  val link = system.actorOf(Props(new DSLinkActor(ci) {}), "link")
  val probe = TestProbe()

  "DSLinkActor" should {
    "return link info" in {
      link ! DSLinkActor.GetLinkInfo
      expectMsgType[DSLinkActor.LinkInfo]
    }
  }
}