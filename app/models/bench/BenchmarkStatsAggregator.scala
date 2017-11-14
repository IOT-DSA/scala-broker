package models.bench

import org.joda.time.{ DateTime, Duration => JodaDuration }

import akka.actor.{ Actor, ActorLogging, Props }
import models.bench.BenchmarkRequester.ReqStatsSample
import models.bench.BenchmarkResponder.RspStatsSample

/**
 * Benchmark stats aggregator.
 */
class BenchmarkStatsAggregator extends Actor with ActorLogging {
  import BenchmarkStatsAggregator._

  private val reqStatsById = collection.mutable.Map.empty[String, RequesterStats]
  private var lastReqStatsSample: Option[ReqStatsSample] = None

  private val rspStatsById = collection.mutable.Map.empty[String, ResponderStats]
  private var lastRspStatsSample: Option[RspStatsSample] = None

  override def preStart() = resetStats

  def receive = {
    case ResetStats => resetStats

    case rs @ ReqStatsSample(id, interval, invokesSent, updatesRcvd) =>
      reqStatsById(id) = reqStatsById.getOrElse(id, RequesterStats.Empty).add(rs)
      lastReqStatsSample = Some(rs)

    case rs @ RspStatsSample(id, interval, invokesRcvd, updatesSent) =>
      rspStatsById(id) = rspStatsById.getOrElse(id, ResponderStats.Empty).add(rs)
      lastRspStatsSample = Some(rs)

    case GetRequesterStats(id) => sender ! reqStatsById.getOrElse(id, RequesterStats.Empty)

    case GetResponderStats(id) => sender ! rspStatsById.getOrElse(id, ResponderStats.Empty)

    case GetAllStats           => sender ! AllStats(reqStatsById.toMap, rspStatsById.toMap)

    case GetGlobalStats =>
      val now = DateTime.now
      val reqStats = {
        val invokesSent = reqStatsById.values.map(_.invokesSent).sum
        val updatesRcvd = reqStatsById.values.map(_.updatesRcvd).sum
        RequesterStats(lastReqStatsSample, totalReqDuration, invokesSent, updatesRcvd)
      }
      val rspStats = {
        val invokesRcvd = rspStatsById.values.map(_.invokesRcvd).sum
        val updatesSent = rspStatsById.values.map(_.updatesSent).sum
        ResponderStats(lastRspStatsSample, totalRspDuration, invokesRcvd, updatesSent)
      }
      sender ! GlobalStats(reqStats, rspStats)

    case GetLastStats => sender ! LastStats(lastReqStatsSample, lastRspStatsSample)
  }

  private def resetStats() = {
    reqStatsById.clear
    lastReqStatsSample = None

    rspStatsById.clear
    lastRspStatsSample = None
  }

  private def totalReqDuration = if (!reqStatsById.isEmpty)
    new JodaDuration(reqStatsById.values.map(_.duration.getMillis).max)
  else
    JodaDuration.ZERO

  private def totalRspDuration = if (!rspStatsById.isEmpty)
    new JodaDuration(rspStatsById.values.map(_.duration.getMillis).max)
  else
    JodaDuration.ZERO
}

/**
 * Factory for [[BenchmarkStatsAggregator]] instances.
 */
object BenchmarkStatsAggregator {
  import AbstractEndpointActor._

  /* request messages */

  case class GetRequesterStats(id: String)

  case class GetResponderStats(id: String)

  case object GetAllStats

  case object GetGlobalStats

  case object GetLastStats

  case object ResetStats

  /* response messages */

  case class RequesterStats(lastSample: Option[ReqStatsSample], duration: JodaDuration,
                            invokesSent: Int, updatesRcvd: Int) extends ReqStatsBehavior {
    def add(sample: ReqStatsSample) = RequesterStats(Some(sample), duration plus sample.duration,
      invokesSent + sample.invokesSent, updatesRcvd + sample.updatesRcvd)
  }

  object RequesterStats {
    val Empty = RequesterStats(None, JodaDuration.ZERO, 0, 0)
  }

  case class ResponderStats(lastSample: Option[RspStatsSample], duration: JodaDuration,
                            invokesRcvd: Int, updatesSent: Int) extends RspStatsBehavior {
    def add(sample: RspStatsSample) = ResponderStats(Some(sample), duration plus sample.duration,
      invokesRcvd + sample.invokesRcvd, updatesSent + sample.updatesSent)
  }

  object ResponderStats {
    val Empty = ResponderStats(None, JodaDuration.ZERO, 0, 0)
  }

  case class AllStats(reqStats: Map[String, RequesterStats], rspStats: Map[String, ResponderStats])

  case class GlobalStats(reqStats: RequesterStats, rspStats: ResponderStats)

  case class LastStats(reqStats: Option[ReqStatsSample], rspStats: Option[RspStatsSample])

  /**
   * Creates a new instance of [[BenchmarkStatsAggregator]] Props.
   */
  def props = Props(new BenchmarkStatsAggregator)
}