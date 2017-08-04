package models.akka

import scala.concurrent.duration.DurationInt

import org.scalatest.Inside

import akka.actor.{ PoisonPill, Props }
import akka.pattern.ask
import akka.testkit.{ TestActorRef, TestProbe }
import akka.util.Timeout
import akka.testkit.TestActors

/**
 * AbstractDSLinkActor test suite.
 */
class AbstractDSLinkActorSpec extends AbstractActorSpec with Inside {
  import AbstractDSLinkActorSpec._
  import Messages._
  import BackendActor._

  implicit val timeout = Timeout(5 seconds)
  val dsId = "link" + "?" * 44

  val dslink = TestActorRef[LinkActor](Props(new LinkActor), "abc")

  val Seq(endpoint1, endpoint2, endpoint3) = (1 to 3) map (_ => watch(TestProbe().ref))

  val ci = ConnectionInfo(dsId, "abc", true, false)
  
  val backendProbe = TestProbe()
  val backend = system.actorOf(TestActors.forwardActorProps(backendProbe.ref), "backend")

  "AbstractDSLinkActor" should {
    "start in disconnected state" in {
      whenReady(dslink ? GetLinkInfo) {
        _ mustBe LinkInfo(ConnectionInfo("", "abc", true, false), false, None, None)
      }
    }
    "connect to endpoint and register with backend" in {
      dslink ! ConnectEndpoint(endpoint1, ci)
      backendProbe.expectMsg(RegisterDSLink("abc", DSLinkMode.Requester, true))
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, true, Some(_), None) => connInfo mustBe ci
      })
    }
    "connect to another endpoint" in {
      dslink ! ConnectEndpoint(endpoint2, ci)
      expectTerminated(endpoint1)
      backendProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, false))
      backendProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, true))
    }
    "disconnect from endpoint" in {
      dslink ! DisconnectEndpoint(false)
      backendProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, false))
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, false, Some(_), Some(_)) => connInfo mustBe ci
      })
    }
    "disconnect from endpoint and kill it" in {
      dslink ! ConnectEndpoint(endpoint3, ci)
      backendProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, true))
      dslink ! DisconnectEndpoint(true)
      backendProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, false))
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, false, Some(_), Some(_)) => connInfo mustBe ci
      })
      expectTerminated(endpoint3)
    }
    "respond to endpoint termination" in {
      dslink ! ConnectEndpoint(endpoint2, ci)
      backendProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, true))
      endpoint2 ! PoisonPill
      backendProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, false))
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, false, Some(_), Some(_)) => connInfo mustBe ci
      })
    }
    "unregister from backend" in {
      dslink ! PoisonPill
      backendProbe.expectMsg(UnregisterDSLink("abc"))
    }
  }
}

/**
 * Common definitions for [[AbstractDSLinkActorSpec]].
 */
object AbstractDSLinkActorSpec {

  /**
   * Test actor.
   */
  class LinkActor extends AbstractDSLinkActor {
    def dsaSend(to: String, msg: Any): Unit = {}
  }
}