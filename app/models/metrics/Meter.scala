package models.metrics

import kamon.Kamon
import models.akka.ConnectionInfo

trait Meter {

  def meterTags(tags:String*) = tags foreach{
    Kamon.gauge(_).increment()
  }

  def meterTagsNTimes(tags:String*)(count:Int = 1) = tags foreach{
    Kamon.gauge(_).increment(count)
  }

  def incrementTags(tags:String*) = tags foreach{
    Kamon.gauge(_).increment()
  }

  def decrementTags(tags:String*) = tags foreach{
    Kamon.gauge(_).decrement()
  }

  def tagsForConnection(prefix:String)(ci:ConnectionInfo) = Array(
    prefix,
    s"$prefix.mode.${ci.mode}",
    s"$prefix.brokerAddress.${ci.brokerAddress}",
    s"$prefix.version.${if(ci.version.nonEmpty) ci.version else "undefined"}",
    s"$prefix.compression.${ci.compression}"
  )

  def messageTags(prefix:String, ci:ConnectionInfo) = Array(
    prefix,
    s"$prefix.brokerAddress.${ci.brokerAddress}"
  )

  def tagsWithPrefix(prefix:String)(in: String*) = {
    prefix + in.map(t => s"$prefix.$t")
  }

}
