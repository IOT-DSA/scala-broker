package models.akka.local

import scala.concurrent.duration.DurationInt

import org.scalatest.Inside

import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.routing.ActorRefRoutee
import akka.util.Timeout
import models.{ RequestEnvelope, ResponseEnvelope, Settings }
import models.akka.{ AbstractActorSpec, DSLinkMode, IsNode, rows }
import models.rpc.{ CloseRequest, DSAResponse, ListRequest }
import akka.actor.Address

/**
  * LocalDSLinkFolderActor test suite.
  */
class LocalDSLinkFolderActorSpec extends AbstractActorSpec with Inside {
  import models.Settings._
  import models.akka.Messages._
  import models.rpc.DSAValue._

  type FoundLinks = Map[Address, Iterable[String]]

  implicit val timeout = Timeout(3 seconds)
  val dsId = "link" + "?" * 44

  val dslinkMgr = new LocalDSLinkManager(nullDaos)
  val downstream = system.actorOf(LocalDSLinkFolderActor.props(
    Paths.Downstream, dslinkMgr.dnlinkProps, "downstream" -> true), Nodes.Downstream)

  "GetOrCreateDSLink" should {
    "create a new dslink" in {
      whenReady(downstream ? GetOrCreateDSLink("aaa")) { result =>
        result mustBe a[ActorRefRoutee]
        val link = result.asInstanceOf[ActorRefRoutee]
        link.ref.path.parent mustBe downstream.path
        link.ref.path.name mustBe "aaa"
      }
      whenReady(downstream ? GetOrCreateDSLink("bbb")) { result =>
        result mustBe a[ActorRefRoutee]
        val link = result.asInstanceOf[ActorRefRoutee]
        link.ref.path.parent mustBe downstream.path
        link.ref.path.name mustBe "bbb"
      }
    }
    "return an existing DSLink actor" in {
      whenReady(downstream ? GetOrCreateDSLink("aaa")) { result =>
        result mustBe a[ActorRefRoutee]
        val link = result.asInstanceOf[ActorRefRoutee]
        link.ref.path.parent mustBe downstream.path
        link.ref.path.name mustBe "aaa"
      }
    }
  }

  "RegisterDSLink" should {
    "record link names" in {
      downstream ! RegisterDSLink("aaa", DSLinkMode.Requester, false)
      downstream ! RegisterDSLink("bbb", DSLinkMode.Requester, false)
      downstream ! DSLinkStateChanged("bbb", DSLinkMode.Responder, false)
      whenReady((downstream ? GetDSLinkNames).mapTo[Iterable[String]]) {
        _.toSet mustBe Set("aaa", "bbb")
      }
    }
    "record link stats" in {
      Thread.sleep(500)
      whenReady((downstream ? GetDSLinkStats).mapTo[DSLinkStats]) {
        _.nodeStats.values.toList mustBe List(DSLinkNodeStats(downstream.path.address, 0, 1, 0, 1, 0, 0))
      }
    }
  }

  "DSLinkStateChanged" should {
    "handle change dslink state" in {
      downstream ! DSLinkStateChanged("bbb", DSLinkMode.Responder, true)
      whenReady((downstream ? GetDSLinkStats).mapTo[DSLinkStats]) {
        _.nodeStats.values.toList mustBe List(DSLinkNodeStats(downstream.path.address, 0, 1, 1, 0, 0, 0))
      }
      downstream ! DSLinkStateChanged("bbb", DSLinkMode.Responder, false)
      whenReady((downstream ? GetDSLinkStats).mapTo[DSLinkStats]) {
        _.nodeStats.values.toList mustBe List(DSLinkNodeStats(downstream.path.address, 0, 1, 0, 1, 0, 0))
      }
    }
  }

  "FindDSLinks" should {
    "search for matching dslinks" in {
      whenReady((downstream ? FindDSLinks("a.*", 100, 0)).mapTo[FoundLinks]) { result =>
        result.size mustBe 1
        result.values.head mustBe List("aaa")
      }
    }
  }

  "ListRequest" should {
    "return all dslinks" in {
      downstream ! RequestEnvelope(List(ListRequest(1, "/downstream")))
      inside(receiveOne(timeout.duration)) {
        case ResponseEnvelope(List(DSAResponse(1, Some(open), Some(list), _, _))) =>
          list mustBe rows(IsNode, "downstream" -> true, "aaa" -> obj(IsNode), "bbb" -> obj(IsNode))
      }
    }
    "send updates on added nodes" in {
      downstream ! GetOrCreateDSLink("ccc")
      val Seq(routee, env) = receiveN(2)
      inside(env) {
        case ResponseEnvelope(List(DSAResponse(1, Some(open), Some(list), _, _))) =>
          list mustBe rows("ccc" -> obj(IsNode))
      }
    }
    "send updates on removed nodes" in {
      downstream ! RemoveDSLink("ccc")
      inside(receiveOne(timeout.duration)) {
        case ResponseEnvelope(List(DSAResponse(1, Some(open), Some(list), _, _))) =>
          list mustBe List(obj("name" -> "ccc", "change" -> "remove"))
      }
    }
  }

  "CloseRequest" should {
    "return valid response" in {
      downstream ! RequestEnvelope(List(CloseRequest(1)))
      downstream ! GetOrCreateDSLink("ddd")
      expectMsgClass(classOf[ActorRefRoutee])
      expectNoMessage(timeout.duration)
    }
  }

  "RemoveDisconnectedDSLinks" should {
    "remove all disconnected dslinks" in {
      downstream ! RemoveDisconnectedDSLinks
      Thread.sleep(500)
      whenReady((downstream ? GetDSLinkStats).mapTo[DSLinkStats]) {
        _.nodeStats.values.toList mustBe List(DSLinkNodeStats(downstream.path.address, 0, 0, 0, 0, 0, 0))
      }
    }
  }
}