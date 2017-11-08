package models.bench

import org.joda.time.Duration
import org.scalatestplus.play.PlaySpec

/**
 * Test suite for statistics classes.
 */
class StatsSpec extends PlaySpec {

  "Sample.perSec" should {
    "compute for valid intervals" in {
      Sample(5, Duration.standardSeconds(10)).perSec mustBe 0.5
      Sample(20, Duration.standardSeconds(5)).perSec mustBe 4
    }
    "be zero for empty intervals" in {
      Sample(3, Duration.standardSeconds(0)).perSec mustBe 0.0
    }
  }

  "Stats.isEmpty" should {
    "return true for empty Stats" in {
      new StatsBuffer().isEmpty mustBe true
    }
    "return false for non-empty Stats" in {
      new StatsBuffer().add(3, Duration.standardSeconds(3)).isEmpty mustBe false
    }
  }

  "Stats.last" should {
    "return last sample" in {
      val sample = Sample(3, Duration.standardSeconds(3))
      new StatsBuffer().add(sample).last mustBe Some(sample)
    }
    "return None for empty Stats" in {
      new StatsBuffer().last mustBe None
    }
  }

  "Stats.total" should {
    "equal last for a single sample" in {
      val sample = Sample(25, Duration.standardSeconds(10))
      new StatsBuffer().add(sample).total mustBe sample
    }
    "be sum of all samples" in {
      val sample1 = Sample(6, Duration.standardSeconds(1))
      val sample2 = Sample(10, Duration.standardSeconds(5))
      val sample3 = Sample(4, Duration.standardSeconds(8))
      new StatsBuffer().add(sample1).add(sample2).add(sample3).total mustBe Sample(20, Duration.standardSeconds(14))
    }
    "be empty for empty stats" in {
      new StatsBuffer().total mustBe Sample.Empty
    }
  }
}