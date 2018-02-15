package models.akka.local

import scala.concurrent.duration.DurationInt

import org.scalatest.Inside

import akka.pattern.ask
import akka.routing.ActorSelectionRoutee
import akka.util.Timeout
import models.{ RequestEnvelope, ResponseEnvelope, Settings }
import models.akka.{ AbstractActorSpec, RootNodeActor }
import models.akka.Messages.{ GetDSLinkNames, GetLinkInfo, GetOrCreateDSLink, LinkInfo }
import models.rpc.{ DSAResponse, ListRequest }

/**
 * LocalDSLinkManager test suite.
 */
class LocalDSLinkManagerSpec extends AbstractActorSpec with Inside {

  implicit val timeout = Timeout(3 seconds)

  val mgr = new LocalDSLinkManager(nullDaos)
  val downstream = system.actorOf(LocalDSLinkFolderActor.props(Settings.Paths.Downstream, mgr.dnlinkProps), Settings.Nodes.Downstream)
  val upstream = system.actorOf(LocalDSLinkFolderActor.props(Settings.Paths.Upstream, mgr.uplinkProps), Settings.Nodes.Upstream)
  system.actorOf(RootNodeActor.props, Settings.Nodes.Root)

  "getDownlinkRoutee" should {
    "return a actor selection routee" in {
      val routee = mgr.getDownlinkRoutee("aaa")
      routee mustBe a[ActorSelectionRoutee]
      val link = routee.asInstanceOf[ActorSelectionRoutee]
      link.selection mustBe system.actorSelection("/user/downstream/aaa")
    }
  }

  "getUplinkRoutee" should {
    "return a actor selection routee" in {
      val routee = mgr.getUplinkRoutee("aaa")
      routee mustBe a[ActorSelectionRoutee]
      val link = routee.asInstanceOf[ActorSelectionRoutee]
      link.selection mustBe system.actorSelection("/user/upstream/aaa")
    }
  }

  "dsaSend" should {
    "send a message to /downstream node" in {
      mgr.dsaSend("/downstream", GetDSLinkNames)
      expectMsg(Set.empty)
    }
    "send a message to a downlink" in {
      whenReady(downstream ? GetOrCreateDSLink("abc")) { _ =>
        mgr.dsaSend(s"/downstream/abc", GetLinkInfo)
        expectMsgType[LinkInfo]
      }
    }
    "send a message to /upstream node" in {
      mgr.dsaSend("/upstream", GetDSLinkNames)
      expectMsg(Set.empty)
    }
    "send a message to an uplink" in {
      whenReady(upstream ? GetOrCreateDSLink("abc")) { _ =>
        mgr.dsaSend(s"/upstream/abc", GetLinkInfo)
        expectMsgType[LinkInfo]
      }
    }

    "send a message to the top node" in {
      mgr.dsaSend("/", RequestEnvelope(ListRequest(1, "/") :: Nil))
      inside(receiveOne(timeout.duration)) {
        case ResponseEnvelope(DSAResponse(1, Some(closed), _, _, _) :: Nil) => true
      }
    }
    "send a message to a /sys node" in {
      mgr.dsaSend("/sys", RequestEnvelope(ListRequest(2, "/sys") :: Nil))
      inside(receiveOne(timeout.duration)) {
        case ResponseEnvelope(DSAResponse(2, _, _, _, _) :: Nil) => true
      }
    }
    "send a message to a /defs/profile node" in {
      mgr.dsaSend("/defs/profile", RequestEnvelope(ListRequest(3, "/defs/profile") :: Nil))
      inside(receiveOne(timeout.duration)) {
        case ResponseEnvelope(DSAResponse(3, _, _, _, _) :: Nil) => true
      }
    }
  }
}