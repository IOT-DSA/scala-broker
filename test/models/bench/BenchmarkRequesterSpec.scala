package models.bench

import scala.concurrent.duration.DurationInt

import org.scalatest.Inside

import akka.actor.PoisonPill
import akka.routing.ActorRefRoutee
import akka.testkit.TestProbe
import models.akka.{ AbstractActorSpec, ConnectionInfo }
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
  val routee = new ActorRefRoutee(probe.ref)
  val stats = TestProbe()
  val config = BenchmarkRequesterConfig(true, nodePath, 5, 200 milliseconds, true, 100 milliseconds, Some(stats.ref))
  val requester = system.actorOf(BenchmarkRequester.props(reqName, routee, config))

  "BenchmarkRequester" should {
    "register with proxy" in {
      val ci = ConnectionInfo(reqName + "0" * 44, reqName, true, false, None, "1.1.2", List("json"))
      probe.expectMsg(ConnectEndpoint(ci, requester))
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
        case ReqStatsSample(_, _, _, _) => 1
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