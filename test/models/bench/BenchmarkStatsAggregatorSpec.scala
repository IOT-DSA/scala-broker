package models.bench

import java.util.concurrent.TimeUnit

import org.joda.time.{ DateTime, Duration => JodaDuration, Interval }
import org.scalatest.Inside

import BenchmarkRequester.ReqStatsSample
import BenchmarkResponder.RspStatsSample
import BenchmarkStatsAggregator._
import akka.pattern.ask
import akka.util.Timeout
import models.akka.AbstractActorSpec
import scala.concurrent.duration._

/**
 * Test suite for BenchmarkStatsAggregator.
 */
class BenchmarkStatsAggregatorSpec extends AbstractActorSpec with Inside {

  implicit val timeout = Timeout(5 seconds)

  val aggregator = system.actorOf(BenchmarkStatsAggregator.props)

  "BenchmarkStatsAggregator" should {
    "calculate requester stats" in {
      aggregator ! ReqStatsSample("a", interval(10), 2, 3)
      aggregator ! ReqStatsSample("a", interval(5), 4, 1)
      aggregator ! ReqStatsSample("b", interval(4), 3, 7)
      whenReady(aggregator ? GetRequesterStats("a")) {
        _ mustBe RequesterStats(Some(ReqStatsSample("a", interval(5), 4, 1)), duration(15), 6, 4)
      }
      whenReady(aggregator ? GetRequesterStats("b")) {
        _ mustBe RequesterStats(Some(ReqStatsSample("b", interval(4), 3, 7)), duration(4), 3, 7)
      }
      whenReady(aggregator ? GetRequesterStats("x")) {
        _ mustBe RequesterStats(None, JodaDuration.ZERO, 0, 0)
      }
    }
    "calculate responder stats" in {
      aggregator ! RspStatsSample("c", interval(4), 1, 2)
      aggregator ! RspStatsSample("d", interval(6), 3, 2)
      aggregator ! RspStatsSample("d", interval(2), 0, 5)
      aggregator ! RspStatsSample("c", interval(5), 4, 3)
      whenReady(aggregator ? GetResponderStats("c")) {
        _ mustBe ResponderStats(Some(RspStatsSample("c", interval(5), 4, 3)), duration(9), 5, 5)
      }
      whenReady(aggregator ? GetResponderStats("d")) {
        _ mustBe ResponderStats(Some(RspStatsSample("d", interval(2), 0, 5)), duration(8), 3, 7)
      }
      whenReady(aggregator ? GetResponderStats("x")) {
        _ mustBe ResponderStats(None, JodaDuration.ZERO, 0, 0)
      }
    }
    "return all stats" in {
      whenReady(aggregator ? GetAllStats)(inside(_) {
        case AllStats(reqStats, rspStats) =>
          reqStats.keySet mustBe Set("a", "b")
          rspStats.keySet mustBe Set("c", "d")
      })
    }
    "calculate global stats" in {
      whenReady(aggregator ? GetGlobalStats)(inside(_) {
        case GlobalStats(reqStats, rspStats) =>
          reqStats.invokesSent mustBe 9
          reqStats.updatesRcvd mustBe 11
          rspStats.invokesRcvd mustBe 8
          rspStats.updatesSent mustBe 12
      })
    }
    "return last stats" in {
      whenReady(aggregator ? GetLastStats)(inside(_) {
        case LastStats(reqStats, rspStats) =>
          reqStats mustBe Some(ReqStatsSample("b", interval(4), 3, 7))
          rspStats mustBe Some(RspStatsSample("c", interval(5), 4, 3))
      })
    }
    "reset stats" in {
      aggregator ! ResetStats
      whenReady(aggregator ? GetLastStats) {
        _ mustBe LastStats(None, None)
      }
      whenReady(aggregator ? GetAllStats) {
        _ mustBe AllStats(Map.empty, Map.empty)
      }
    }
  }

  private def interval(seconds: Int) = new Interval(new DateTime(2017, 1, 1, 0, 0), duration(seconds))

  private def duration(seconds: Int) = JodaDuration.standardSeconds(seconds)
}