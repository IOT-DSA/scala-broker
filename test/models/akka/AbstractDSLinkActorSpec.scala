package models.akka

import scala.concurrent.duration.DurationInt
import org.scalatest.Inside
import akka.actor.{ActorRef, PoisonPill, Props}
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

  implicit val timeout = Timeout(5 seconds)

  val linkName = "link-abc-1"

  val ci = ConnectionInfo("link" + "?" * 44, linkName, true, false, Some("link-data-some-val"), "version777",
    List("format1", "format2"), true, "link address 1-2-3", "broker address 3-2-1")

  val downProbe = TestProbe()

  val dslink = TestActorRef[LinkActor](Props(new LinkActor(ActorRefRoutee(downProbe.ref))), linkName)

  val Seq(endpoint1, endpoint2, endpoint3) = (1 to 3) map (_ => watch(TestProbe().ref))

  "AbstractDSLinkActor" should {
    "register with downstream" in {
      downProbe.expectMsg(RegisterDSLink(linkName, DSLinkMode.Requester, false))
    }
    Thread.sleep(500)
    "start in disconnected state" in {
      whenReady(dslink ? GetLinkInfo) { x =>
        val y = x.asInstanceOf[LinkInfo].ci
        x mustBe LinkInfo(ConnectionInfo("", linkName, true, false, sharedSecret = y.sharedSecret), false, None, None)
      }
    }
    "connect to endpoint and register with downstream" in {
      dslink.tell(ConnectEndpoint(ci), endpoint1)
      downProbe.expectMsg(DSLinkStateChanged(linkName, DSLinkMode.Requester, true))
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, true, Some(_), None) => connInfo mustBe ci
      })
    }
    "connect to another endpoint" in {
      dslink.tell(ConnectEndpoint(ci), endpoint2)
      expectTerminated(endpoint1)
      downProbe.expectMsg(DSLinkStateChanged(linkName, DSLinkMode.Requester, false))
      downProbe.expectMsg(DSLinkStateChanged(linkName, DSLinkMode.Requester, true))
    }
    "disconnect from endpoint" in {
      dslink ! DisconnectEndpoint(false)
      downProbe.expectMsg(DSLinkStateChanged(linkName, DSLinkMode.Requester, false))
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, false, Some(_), Some(_)) => connInfo mustBe ci
      })
    }
    "disconnect from endpoint and kill it" in {
      dslink.tell(ConnectEndpoint(ci), endpoint3)
      downProbe.expectMsg(DSLinkStateChanged(linkName, DSLinkMode.Requester, true))
      dslink ! DisconnectEndpoint(true)
      downProbe.expectMsg(DSLinkStateChanged(linkName, DSLinkMode.Requester, false))
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, false, Some(_), Some(_)) => connInfo mustBe ci
      })
      expectTerminated(endpoint3)
    }
    "respond to endpoint termination" in {
      dslink.tell(ConnectEndpoint(ci), endpoint2)
      downProbe.expectMsg(DSLinkStateChanged(linkName, DSLinkMode.Requester, true))
      endpoint2 ! PoisonPill
      downProbe.expectMsg(DSLinkStateChanged(linkName, DSLinkMode.Requester, false))
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, false, Some(_), Some(_)) => connInfo mustBe ci
      })
    }
    "unregister from backend" in {
      dslink ! PoisonPill
      downProbe.expectMsg(UnregisterDSLink(linkName))
    }

//    "recover self state" in {
//      val dslinkRecovered = TestActorRef[LinkActor](Props(new LinkActor(ActorRefRoutee(downProbe.ref))), linkName)
//      downProbe.expectMsg(RegisterDSLink(linkName, DSLinkMode.Requester, false))
//      Thread.sleep(500)
//      whenReady(dslinkRecovered ? GetLinkInfo)(inside(_) {
//        case LinkInfo(connInfo, _, _, _) => connInfo mustBe ci
//      })
//
//      // finally kill it
//      dslinkRecovered ! PoisonPill
//      downProbe.expectMsg(UnregisterDSLink(linkName))
//
//      // TODO Create test cases to check lastConnected and lastDisconnected dates
//    }
  }
}

/**
 * Common definitions for [[AbstractDSLinkActorSpec]].
 */
object AbstractDSLinkActorSpec {

  /**
   * Test actor.
   */
  class LinkActor(registry: Routee) extends AbstractDSLinkActor(registry) {
    override def persistenceId = linkName
    override def receiveRecover = recoverBaseState orElse recoverDSLinkSnapshot

    /**
      * Returns a [[Routee]] that can be used for sending messages to a specific downlink.
      */
    override def getDownlinkRoutee(dsaName: String): Routee = ???

    /**
      * Returns a [[Routee]] that can be used for sending messages to a specific uplink.
      */
    override def getUplinkRoutee(dsaName: String): Routee = ???

    override def updateRoutee(routee: Routee): Routee = ???
  }
}
