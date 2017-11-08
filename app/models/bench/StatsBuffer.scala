package models.bench

import org.joda.time.Duration

/**
 * Contains the number of events occurring in a given time interval.
 */
case class Sample(count: Int, duration: Duration) {
  val perSec = if (duration == Duration.ZERO) 0.0 else count.toDouble / duration.getStandardSeconds
}

/**
 * Factory for [[Sample]] instances.
 */
object Sample {
  val Empty = Sample(0, Duration.ZERO)
}

/**
 * Collects the statistics built on individual samples.
 */
class StatsBuffer {
  private var _last: Option[Sample] = None
  private var _total: Sample = Sample.Empty

  def isEmpty = _last.isEmpty

  def add(sample: Sample): StatsBuffer = {
    _last = Some(sample)
    _total = Sample(_total.count + sample.count, _total.duration plus sample.duration)
    this
  }

  def add(count: Int, duration: Duration): StatsBuffer = add(Sample(count, duration))

  def last = _last

  def total = _total
  
  def toStats = Stats(_last, _total)
}

/**
 * Read-only version of StatsBuffer.
 */
case class Stats(last: Option[Sample], total: Sample)

/**
 * Factory for [[Stats]] instances.
 */
object Stats {
  val Empty = Stats(None, Sample.Empty)
}