package controllers

import akka.actor.Address
import models.bench.BenchmarkActor.RequesterTarget

/**
 * Object passed to a view to supply various page information.
 */
case class ViewConfig(title: String, navItem: String, heading: String, description: String,
                      downCount: Option[Int] = None, upCount: Option[Int] = None)

/**
 * Broker node info.
 */
case class NodeInfo(address: Address, leader: Boolean, uid: Long, roles: Set[String], status: String)

/**
 * Benchmark configuration.
 */
case class BenchmarkConfig(subscribe: Boolean, reqCount: Int, rspCount: Int, rspNodeCount: Int,
                           batchSize: Int, batchTimeout: Long, parseJson: Boolean) {

  val expectedInvokeRate = (reqCount * batchSize * 1000L / batchTimeout).toInt

  def calculateExpectedUpdateToInvokeRatio(targets: Iterable[RequesterTarget]) = if (subscribe) {
    val expectedEvents = targets.groupBy(identity).map(_._2.size).map(a => a * a).sum
    (expectedEvents + reqCount).toDouble / reqCount
  } else 1.0
}

/**
 * Factory for [[BenchmarkConfig]] instances.
 */
object BenchmarkConfig {
  val Default = BenchmarkConfig(false, 5, 5, 10, 100, 1000L, false)
}