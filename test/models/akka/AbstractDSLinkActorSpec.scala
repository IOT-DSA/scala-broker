package models.akka

import scala.concurrent.duration.{Duration, DurationInt}
import org.scalatest.Inside
import org.scalatest.time.{Millis, Seconds, Span}
import akka.actor.{ActorRef, PoisonPill, Props, Terminated}
import akka.pattern.ask
import akka.routing.{ActorRefRoutee, Routee}
import akka.testkit.{TestActorRef, TestProbe}
import akka.util.Timeout

/**
 * AbstractDSLinkActor test suite.
 */
class AbstractDSLinkActorSpec extends AbstractActorSpec with Inside {
  import AbstractDSLinkActorSpec._
  import Messages._

//  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(10, Seconds), interval = Span(5, Seconds))

  implicit val timeout = Timeout(5 seconds)
  val dsId = "link" + "?" * 44

  val ci = ConnectionInfo(dsId, "abc", true, false)

  val downProbe = TestProbe()

  val dslink = TestActorRef[LinkActor](Props(new LinkActor(ActorRefRoutee(downProbe.ref))), "abc")

  val Seq(endpoint1, endpoint2, endpoint3) = (1 to 3) map (_ => watch(TestProbe().ref))

  "AbstractDSLinkActor" should {
    "register with downstream" in {
      downProbe.expectMsg(RegisterDSLink("abc", DSLinkMode.Requester, false))
    }
    "start in disconnected state" in {
      whenReady(dslink ? GetLinkInfo) {
        _ mustBe LinkInfo(ConnectionInfo("", "abc", true, false), false, None, None)
      }
    }
    "connect to endpoint and register with downstream" in {
      dslink ! ConnectEndpoint(endpoint1, ci)
      downProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, true))
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, true, Some(_), None) => connInfo mustBe ci
      })
    }
    "connect to another endpoint" in {
      dslink ! ConnectEndpoint(endpoint2, ci)
      expectTerminated(endpoint1)
      downProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, false))
      downProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, true))
    }
    "disconnect from endpoint" in {
      dslink ! DisconnectEndpoint(false)
      downProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, false))
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, false, Some(_), Some(_)) => connInfo mustBe ci
      })
    }
    "disconnect from endpoint and kill it" in {
      dslink ! ConnectEndpoint(endpoint3, ci)
      downProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, true))
      dslink ! DisconnectEndpoint(true)
      downProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, false))
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, false, Some(_), Some(_)) => connInfo mustBe ci
      })
      expectTerminated(endpoint3)
    }
    "respond to endpoint termination" in {
      dslink ! ConnectEndpoint(endpoint2, ci)
      downProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, true))
      endpoint2 ! PoisonPill
      downProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, false))
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, false, Some(_), Some(_)) => connInfo mustBe ci
      })
    }
    "unregister from backend" in {
      dslink ! PoisonPill
      downProbe.expectMsg(UnregisterDSLink("abc"))
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
  class LinkActor(registry: Routee) extends AbstractDSLinkActor(registry)
}