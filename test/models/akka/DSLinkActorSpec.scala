package models.akka

import com.typesafe.config.ConfigFactory

import akka.actor.{ Props, actorRef2Scala }
import akka.stream.scaladsl.Flow
import akka.testkit.TestProbe
import models.Settings
import models.rpc.DSAMessage
import play.api.Configuration

/**
 * DSLinkActor test suite.
 */
class DSLinkActorSpec extends AbstractActorSpec {
  
  val ci = ConnectionInfo("", "", true, false)

  val link = system.actorOf(Props(new DSLinkActor(ci) {}), "link")
  val probe = TestProbe()

  "DSLinkActor" should {
    "start a WebSocket flow" in {
      link ! DSLinkActor.StartWSFlow
      expectMsgType[Flow[DSAMessage, DSAMessage, _]]
    }
  }
}