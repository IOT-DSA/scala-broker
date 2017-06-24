package models.akka

import akka.testkit.TestKit
import akka.testkit.ImplicitSender
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import akka.actor.{ ActorRef, ActorSystem, InvalidActorNameException }

import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout
import models.Settings
import play.api.Configuration
import com.typesafe.config.ConfigFactory

/**
 * DownstreamActor test suite.
 */
class DownstreamActorSpec extends AbstractActorSpec {
  import DownstreamActor._

  implicit val timeout = Timeout(5 seconds)
  val dsId = "link" + "?" * 44

  val downstream = system.actorOf(props, Settings.Nodes.Downstream)

  "CreateDSLink" should {
    "create a new requester" in {
      val ci = ConnectionInfo(dsId, "requester", true, false)
      whenReady(downstream ? CreateDSLink(ci)) { result =>
        result mustBe a[ActorRef]
        val link = result.asInstanceOf[ActorRef]
        link.path.parent mustBe downstream.path
        link.path.name mustBe "requester"
      }
    }
    "create a new responder" in {
      val ci = ConnectionInfo(dsId, "responder", false, true)
      whenReady(downstream ? CreateDSLink(ci)) { result =>
        result mustBe a[ActorRef]
        val link = result.asInstanceOf[ActorRef]
        link.path.parent mustBe downstream.path
        link.path.name mustBe "responder"
      }
    }
    "create a new dual link" in {
      val ci = ConnectionInfo(dsId, "dual", true, true)
      whenReady(downstream ? CreateDSLink(ci)) { result =>
        result mustBe a[ActorRef]
        val link = result.asInstanceOf[ActorRef]
        link.path.parent mustBe downstream.path
        link.path.name mustBe "dual"
      }
    }
    "fail to create a duplicate DSLink actor" in {
      val ci = ConnectionInfo(dsId, "dual", true, true)
      val future = downstream ? CreateDSLink(ci)
      future.failed.futureValue mustBe an[InvalidActorNameException]
    }
  }

  "GetDSLink" should {
    "find DSLink actor by name" in {
      whenReady(downstream ? GetDSLink("requester")) { result =>
        result mustBe an[Option[_]]
        val optLink = result.asInstanceOf[Option[_]]
        optLink.value mustBe a[ActorRef]
        val link = optLink.get.asInstanceOf[ActorRef]
        link.path.parent mustBe downstream.path
        link.path.name mustBe "requester"
      }
    }
    "return None when searching for non-existent DSLink name" in {
      whenReady(downstream ? GetDSLink("xyz")) { result =>
        result mustBe an[Option[_]]
        val optLink = result.asInstanceOf[Option[_]]
        optLink mustBe None
      }
    }
  }

  "GetOrCreateDSLink" should {
    "get existing DSLink actor" in {
      val ci = ConnectionInfo(dsId, "responder", false, true)
      whenReady(downstream ? GetOrCreateDSLink(ci)) { result =>
        result mustBe a[ActorRef]
        val link = result.asInstanceOf[ActorRef]
        link.path.parent mustBe downstream.path
        link.path.name mustBe "responder"
      }
    }
    "create a new DSLink actor" in {
      val ci = ConnectionInfo(dsId, "responder2", false, true)
      whenReady(downstream ? GetOrCreateDSLink(ci)) { result =>
        result mustBe a[ActorRef]
        val link = result.asInstanceOf[ActorRef]
        link.path.parent mustBe downstream.path
        link.path.name mustBe "responder2"
      }
    }
  }

  "GetDSLinkCount" should {
    "return the number of DSLink actors" in {
      whenReady(downstream ? GetDSLinkCount) { _ mustBe 4 }
    }
  }

  "FindDSLinks" should {
    "find existing links" in {
      whenReady(downstream ? FindDSLinks(".*", 100)) { result =>
        result.asInstanceOf[Iterable[_]].toSet mustBe Set("dual", "requester", "responder", "responder2")
      }
      whenReady(downstream ? FindDSLinks("r.*", 100)) { result =>
        result.asInstanceOf[Iterable[_]].toSet mustBe Set("requester", "responder", "responder2")
      }
      whenReady(downstream ? FindDSLinks("res.*", 1, 1)) { result =>
        result.asInstanceOf[Iterable[_]].toSet mustBe Set("responder2")
      }
      whenReady(downstream ? FindDSLinks("xyz.*", 100)) { result =>
        result.asInstanceOf[Iterable[_]] mustBe empty
      }
    }
  }
}