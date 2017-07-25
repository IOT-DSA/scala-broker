package models.akka.cluster

import scala.concurrent.duration.DurationInt

import org.scalatest.Inside

import akka.pattern.ask
import akka.actor.PoisonPill
import akka.testkit.{ TestActorRef, TestProbe }
import akka.util.Timeout
import models.akka.{ AbstractActorSpec, ConnectionInfo }

/**
 * DSLinkActor test suite.
 */
class DSLinkActorSpec extends AbstractActorSpec with Inside {
  import DSLinkActor._

  implicit val timeout = Timeout(5 seconds)
  val dsId = "link" + "?" * 44

  val dslink = TestActorRef[DSLinkActor](DSLinkActor.props, "abc")

  val endpoint1 = TestProbe()
  val endpoint2 = TestProbe()
  val endpoint3 = TestProbe()

  watch(endpoint1.ref)
  watch(endpoint2.ref)
  watch(endpoint3.ref)

  val ci = ConnectionInfo(dsId, "requester", true, false)

  "DSLinkActor" should {
    "start in disconnected state" in {
      whenReady(dslink ? GetLinkInfo) { _ mustBe LinkInfo(null, false, None, None) }
    }
    "connect to endpoint" in {
      val ci = ConnectionInfo(dsId, "requester", true, false)
      dslink ! ConnectEndpoint(endpoint1.ref, ci)
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, true, Some(_), None) => connInfo mustBe ci
      })
    }
    "connect to another endpoint" in {
      dslink ! ConnectEndpoint(endpoint2.ref, ci)
      expectTerminated(endpoint1.ref)
    }
    "disconnect from endpoint" in {
      dslink ! DisconnectEndpoint(false)
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, false, Some(_), Some(_)) => connInfo mustBe ci
      })
    }
    "disconnect from endpoint and kill it" in {
      dslink ! ConnectEndpoint(endpoint3.ref, ci)
      dslink ! DisconnectEndpoint(true)
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, false, Some(_), Some(_)) => connInfo mustBe ci
      })
      expectTerminated(endpoint3.ref)
    }
    "respond to endpoint termination" in {
      dslink ! ConnectEndpoint(endpoint2.ref, ci)
      endpoint2.ref ! PoisonPill
      whenReady(dslink ? GetLinkInfo)(inside(_) {
        case LinkInfo(connInfo, false, Some(_), Some(_)) => connInfo mustBe ci
      })
    }
  }
}