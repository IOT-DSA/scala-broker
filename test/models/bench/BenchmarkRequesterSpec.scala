package models.bench

import scala.concurrent.duration.DurationInt

import org.scalatest.Inside

import akka.actor.PoisonPill
import akka.testkit.TestProbe
import models.akka.{ AbstractActorSpec, ActorRefProxy, ConnectionInfo }
import models.akka.Messages.ConnectEndpoint
import models.rpc._

/**
 * Test suite for BenchmarkRequester.
 */
class BenchmarkRequesterSpec extends AbstractActorSpec with Inside {
  import BenchmarkRequester._

  val rspName = "BenchRSP"
  val nodePath = "/" + rspName + "/data1"
  val ActionPath = nodePath + "/incCounter"

  val reqName = "BenchRQ"
  val probe = TestProbe()
  val proxy = new ActorRefProxy(probe.ref)
  val stats = TestProbe()
  val config = BenchmarkRequesterConfig(nodePath, 5, 200 milliseconds, 100 milliseconds, Some(stats.ref))
  val requester = system.actorOf(BenchmarkRequester.props(reqName, proxy, config))

  "BenchmarkRequester" should {
    "register with proxy" in {
      val ci = ConnectionInfo(reqName + "0" * 44, reqName, true, false)
      probe.expectMsg(ConnectEndpoint(requester, ci))
    }
    "subscribe to events" in {
      val req = SubscribeRequest(1, SubscriptionPath(nodePath, 101))
      probe.expectMsg(RequestMessage(1, None, List(req)))
    }
    "generate action invocations" in {
      val reqs = probe.receiveWhile(1 second) {
        case RequestMessage(_, _, reqs) => reqs
      }.flatMap(identity)
      reqs.size must be > 10
      reqs.foreach(inside(_) {
        case InvokeRequest(_, ActionPath, _, _) =>
      })
    }
    "emit statistics" in {
      val records = stats.receiveWhile(500 milliseconds) {
        case RequesterStats(_, _, _, _) => 1
      }
      records.sum must be > 2
    }
    "unsubscribe from events on stop" in {
      requester ! PoisonPill
      val reqs = probe.receiveWhile(5 seconds) {
        case RequestMessage(_, _, reqs) => reqs
      }.flatMap(identity)
      inside(reqs.last) {
        case UnsubscribeRequest(_, List(101)) =>
      }
    }
  }
}