package models.metrics

import kamon.Kamon
import models.akka.ConnectionInfo

trait Meter {

  def countTags(tags:String*) = tags foreach{
    Kamon.counter(_).increment()
  }

  def countTagsNTimes(tags:String*)(times:Int) = tags foreach{
    Kamon.counter(_).increment(times)
  }

  def meterTags(tags:String*) = tags foreach{
    Kamon.gauge(_).increment()
  }

  def incrementTagsNTimes(tags:String*)(count:Int = 1) = tags foreach{
    Kamon.gauge(_).increment(count)
  }

  def decrementTagsNTimes(tags:String*)(count:Int = 1) = tags foreach{
    Kamon.gauge(_).decrement(count)
  }

  def incrementTags(tags:String*) = tags foreach{
    Kamon.gauge(_).increment()
  }

  def decrementTags(tags:String*) = tags foreach{
    Kamon.gauge(_).decrement()
  }

  def histogramValue(tags:String*)(times:Int) = tags foreach{
    Kamon.histogram(_).record(times)
  }

  def tagsForConnection(prefix:String)(ci:ConnectionInfo) = Array(
    prefix,
    prefix + ".mode." + ci.mode,
    prefix + ".brokerAddress.${ci.brokerAddress}",
    prefix + ".version." + (if(ci.version.nonEmpty) ci.version else "undefined"),
    prefix + ".compression." + ci.compression
  )

  def messageTags(prefix:String, ci:ConnectionInfo) = Array(
    prefix,
    prefix + ".brokerAddress." + ci.brokerAddress
  )

  def tagsWithPrefix(prefix:String)(in: String*) = {
    prefix + in.map(t => s"$prefix.$t")
  }

}
