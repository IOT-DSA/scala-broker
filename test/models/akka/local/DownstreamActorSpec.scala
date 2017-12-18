package models.akka.local

import akka.testkit._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import akka.actor.{ ActorRef, ActorSystem, InvalidActorNameException }

import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout
import models.Settings
import play.api.Configuration
import com.typesafe.config.ConfigFactory
import models.akka.AbstractActorSpec

/**
 * DownstreamActor test suite.
 */
class DownstreamActorSpec extends AbstractActorSpec {
  import DownstreamActor._

  implicit val timeout = Timeout(5 seconds)
  val dsId = "link" + "?" * 44

  val dslinkMgr = new LocalDSLinkManager
  val downstream = system.actorOf(props(dslinkMgr), Settings.Nodes.Downstream)

  "CreateDSLink" should {
    "create a new dslink" in {
      whenReady(downstream ? CreateDSLink("requester")) { result =>
        result mustBe a[ActorRef]
        val link = result.asInstanceOf[ActorRef]
        link.path.parent mustBe downstream.path
        link.path.name mustBe "requester"
      }
    }
    "fail to create a duplicate DSLink actor" in {
      val future = downstream ? CreateDSLink("requester")
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
      whenReady(downstream ? GetOrCreateDSLink("requester")) { result =>
        result mustBe a[ActorRef]
        val link = result.asInstanceOf[ActorRef]
        link.path.parent mustBe downstream.path
        link.path.name mustBe "requester"
      }
    }
    "create a new DSLink actor" in {
      whenReady(downstream ? GetOrCreateDSLink("responder")) { result =>
        result mustBe a[ActorRef]
        val link = result.asInstanceOf[ActorRef]
        link.path.parent mustBe downstream.path
        link.path.name mustBe "responder"
      }
    }
  }
}