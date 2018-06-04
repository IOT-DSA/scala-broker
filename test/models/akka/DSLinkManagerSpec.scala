package models.akka

import akka.actor.{ ActorRef, ActorSystem }
import akka.routing.Routee
import models.Settings

/**
 * DSLinkManager test suite.
 */
class DSLinkManagerSpec extends AbstractActorSpec {
  import DSLinkManagerSpec._

  val mgr = new TestDSLinkManager(system)

  "downstream" should {
    "point to /user/downstream actor" in {
      mgr.downstream.selection.pathString mustBe "/user" + Settings.Paths.Downstream
    }
  }

  "upstream" should {
    "point to /user/upstream actor" in {
      mgr.upstream.selection.pathString mustBe "/user" + Settings.Paths.Upstream
    }
  }

  "dnlinkProps" should {
    "create downlink Props" in {
      mgr.dnlinkProps.args must contain(classOf[SimpleDSLinkActor])
    }
  }

  "uplinkProps" should {
    "create uplink Props" in {
      mgr.uplinkProps.args must contain(classOf[SimpleDSLinkActor])
    }
  }
}

/**
 * DSLinkManager test utilities.
 */
object DSLinkManagerSpec {
  class TestDSLinkManager(val system: ActorSystem) extends DSLinkManager {
    def dsaSend(path: String, message: Any)(implicit sender: ActorRef): Unit = ???
    def getDownlinkRoutee(name: String): Routee = ???
    def getUplinkRoutee(name: String): Routee = ???
  }
}