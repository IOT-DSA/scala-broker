package models.bench

import java.util.concurrent.TimeUnit

import org.joda.time.{ DateTime, Duration, Interval }
import org.scalatest.Inside

import BenchmarkRequester.RequesterStats
import BenchmarkResponder.ResponderStats
import BenchmarkStats._
import akka.pattern.ask
import akka.util.Timeout
import models.akka.AbstractActorSpec

/**
 * Test suite for BenchmarkStats.
 */
class BenchmarkStatsSpec extends AbstractActorSpec with Inside {

  implicit val timeout = Timeout(scala.concurrent.duration.Duration(5, TimeUnit.SECONDS))

  val statistician = system.actorOf(BenchmarkStats.props)

  "BenchmarkStats" should {
    "calculate requester stats" in {
      statistician ! RequesterStats("a", interval(10), 2, 3)
      statistician ! RequesterStats("a", interval(5), 4, 1)
      statistician ! RequesterStats("b", interval(4), 8, 0)
      whenReady(statistician ? GetRequesterStats("a")) { msg =>
        msg mustBe RequesterData(
          Stats(Some(Sample(4, duration(5))), Sample(6, duration(15))),
          Stats(Some(Sample(1, duration(5))), Sample(4, duration(15))))
      }
      whenReady(statistician ? GetRequesterStats("b")) { msg =>
        msg mustBe RequesterData(
          Stats(Some(Sample(8, duration(4))), Sample(8, duration(4))),
          Stats(Some(Sample(0, duration(4))), Sample(0, duration(4))))
      }
      whenReady(statistician ? GetRequesterStats("x")) { msg =>
        msg mustBe RequesterData(Stats(None, Sample.Empty), Stats(None, Sample.Empty))
      }
    }
    "calculate responder stats" in {
      statistician ! ResponderStats("c", interval(4), 1, 2)
      statistician ! ResponderStats("d", interval(6), 3, 2)
      statistician ! ResponderStats("d", interval(2), 0, 5)
      statistician ! ResponderStats("c", interval(5), 4, 3)
      whenReady(statistician ? GetResponderStats("c")) { msg =>
        msg mustBe ResponderData(
          Stats(Some(Sample(4, duration(5))), Sample(5, duration(9))),
          Stats(Some(Sample(3, duration(5))), Sample(5, duration(9))))
      }
      whenReady(statistician ? GetResponderStats("d")) { msg =>
        msg mustBe ResponderData(
          Stats(Some(Sample(0, duration(2))), Sample(3, duration(8))),
          Stats(Some(Sample(5, duration(2))), Sample(7, duration(8))))
      }
      whenReady(statistician ? GetResponderStats("x")) { msg =>
        msg mustBe ResponderData(Stats(None, Sample.Empty), Stats(None, Sample.Empty))
      }
    }
    "return all stats" in {
      whenReady(statistician ? GetAllStats)(inside(_) {
        case ReqRspData(reqData, rspData) =>
          reqData.keySet mustBe Set("a", "b")
          rspData.keySet mustBe Set("c", "d")
      })
    }
    "return last stats" in {
      whenReady(statistician ? GetLastStats)(inside(_) {
        case LastStats(reqStats, rspStats) =>
          reqStats mustBe Some(RequesterStats("b", interval(4), 8, 0))
          rspStats mustBe Some(ResponderStats("c", interval(5), 4, 3))
      })
    }
    "calculate global stats" in {
      whenReady(statistician ? GetGlobalStats)(inside(_) {
        case GlobalData(reqData, rspData) =>
          reqData mustBe RequesterData(
            Stats(Some(Sample(8, duration(4))), Sample(14, duration(19))),
            Stats(Some(Sample(0, duration(4))), Sample(4, duration(19))))
          rspData mustBe ResponderData(
            Stats(Some(Sample(4, duration(5))), Sample(8, duration(17))),
            Stats(Some(Sample(3, duration(5))), Sample(12, duration(17))))
      })
    }
  }

  private def interval(seconds: Int) = new Interval(new DateTime(2017, 1, 1, 0, 0), duration(seconds))

  private def duration(seconds: Int) = Duration.standardSeconds(seconds)
}