package models.bench

import org.joda.time.{ DateTime, Duration, Interval }
import org.scalatestplus.play.PlaySpec

import models.bench.BenchmarkRequester.ReqStatsSample
import models.bench.BenchmarkResponder.RspStatsSample
import models.bench.BenchmarkStatsAggregator.{ RequesterStats, ResponderStats }

/**
 * Test suite for statistics classes.
 */
class StatsSpec extends PlaySpec {
  import AbstractEndpointActor._

  "StatsSample.ratePerSec" should {
    "calculate rate for valid durations" in {
      val sample = new StatsSample { val duration = Duration.standardSeconds(10) }
      sample.ratePerSec(5) mustBe 0.5
      sample.ratePerSec(20) mustBe 2
    }
    "be zero for empty durations" in {
      val sample = new StatsSample { val duration = Duration.ZERO }
      sample.ratePerSec(5) mustBe 0.0
    }
  }

  "ReqStatsSample" should {
    "calculate invoke sent rate" in {
      ReqStatsSample("a", interval(10), 4, 2).invokesSentPerSec mustBe 0.4
      ReqStatsSample("a", interval(0), 0, 2).invokesSentPerSec mustBe 0.0
    }
    "calculate update received rate" in {
      ReqStatsSample("a", interval(10), 4, 2).updatesRcvdPerSec mustBe 0.2
      ReqStatsSample("a", interval(0), 4, 2).updatesRcvdPerSec mustBe 0.0
    }
  }

  "RspStatsSample" should {
    "calculate invoke received rate" in {
      RspStatsSample("a", interval(10), 4, 2).invokesRcvdPerSec mustBe 0.4
      RspStatsSample("a", interval(0), 0, 2).invokesRcvdPerSec mustBe 0.0
    }
    "calculate update sent rate" in {
      RspStatsSample("a", interval(10), 4, 2).updatesSentPerSec mustBe 0.2
      RspStatsSample("a", interval(0), 4, 2).updatesSentPerSec mustBe 0.0
    }
  }

  "RequesterStats" should {
    "append samples" in {
      val sample1 = ReqStatsSample("a", interval(4), 6, 2)
      val sample2 = ReqStatsSample("a", interval(5), 1, 3)
      val rs = RequesterStats.Empty
      rs.add(sample1).add(sample2) mustBe RequesterStats(Some(sample2), duration(9), 7, 5)
    }
    "calculate invoke sent rate" in {
      RequesterStats.Empty.invokesSentPerSec mustBe 0.0
      RequesterStats(None, duration(0), 3, 3).invokesSentPerSec mustBe 0.0
      RequesterStats(None, duration(10), 3, 3).invokesSentPerSec mustBe 0.3
    }
    "calculate update received rate" in {
      RequesterStats.Empty.updatesRcvdPerSec mustBe 0.0
      RequesterStats(None, duration(0), 3, 3).updatesRcvdPerSec mustBe 0.0
      RequesterStats(None, duration(10), 3, 3).updatesRcvdPerSec mustBe 0.3
    }
  }

  "ResponderStats" should {
    "append samples" in {
      val sample1 = RspStatsSample("a", interval(2), 0, 6)
      val sample2 = RspStatsSample("a", interval(4), 4, 2)
      val rs = ResponderStats.Empty
      rs.add(sample1).add(sample2) mustBe ResponderStats(Some(sample2), duration(6), 4, 8)
    }
    "calculate invoke received rate" in {
      ResponderStats.Empty.invokesRcvdPerSec mustBe 0.0
      ResponderStats(None, duration(0), 3, 3).invokesRcvdPerSec mustBe 0.0
      ResponderStats(None, duration(10), 3, 3).invokesRcvdPerSec mustBe 0.3
    }
    "calculate update sent rate" in {
      ResponderStats.Empty.updatesSentPerSec mustBe 0.0
      ResponderStats(None, duration(0), 3, 3).updatesSentPerSec mustBe 0.0
      ResponderStats(None, duration(10), 3, 3).updatesSentPerSec mustBe 0.3
    }
  }

  private def interval(seconds: Int) = new Interval(new DateTime(2017, 1, 1, 0, 0), duration(seconds))

  private def duration(seconds: Int) = Duration.standardSeconds(seconds)
}