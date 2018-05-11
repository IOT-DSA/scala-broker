package models.mitrics

import models.akka.ConnectionInfo
import nl.grons.metrics4.scala.DefaultInstrumented

trait Meter {
  self:DefaultInstrumented =>

  def meterTags(tags:String*) = tags foreach{
    metrics.meter(_).mark()
  }

  def incrementTags(tags:String*) = tags foreach{
    metrics.counter(_).inc()
  }

  def decrementTags(tags:String*) = tags foreach{
    metrics.counter(_).dec()
  }

  def connectionTags(ci:ConnectionInfo) = Array(
    s"connected",
    s"connected.mode.${ci.mode}",
    s"connected.brokerAddress.${ci.brokerAddress}",
    s"connected.version.${if(ci.version.nonEmpty) ci.version else "undefined"}",
    s"connected.compression.${ci.compression}"
  )

  def messageTags(prefix:String, ci:ConnectionInfo) = Array(
    prefix,
    s"$prefix.brokerAddress.${ci.brokerAddress}"
  )


}
