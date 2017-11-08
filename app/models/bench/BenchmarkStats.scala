package models.bench

import org.joda.time.Duration

import akka.actor.{ Actor, ActorLogging, Props }
import models.bench.BenchmarkRequester.RequesterStats
import models.bench.BenchmarkResponder.ResponderStats

/**
 * Benchmark stats aggregator.
 */
class BenchmarkStats extends Actor with ActorLogging {
  import BenchmarkStats._

  private var lastReqStats: Option[RequesterStats] = None
  private val reqStatsById = collection.mutable.Map.empty[String, RequesterDataBuffer]
  private var globalReqStats: RequesterDataBuffer = RequesterDataBuffer(new StatsBuffer, new StatsBuffer)

  private var lastRspStats: Option[ResponderStats] = None
  private val rspStatsById = collection.mutable.Map.empty[String, ResponderDataBuffer]
  private var globalRspStats: ResponderDataBuffer = ResponderDataBuffer(new StatsBuffer, new StatsBuffer)

  def receive = {
    case rs @ RequesterStats(id, interval, invokesSent, updatesRcvd) =>
      lastReqStats = Some(rs)
      val rd = reqStatsById.getOrElseUpdate(id, RequesterDataBuffer(new StatsBuffer, new StatsBuffer))
      updateRequesterData(interval.toDuration, invokesSent, updatesRcvd)(rd)
      updateRequesterData(interval.toDuration, invokesSent, updatesRcvd)(globalReqStats)

    case rs @ ResponderStats(id, interval, invokesRcvd, updatesSent) =>
      lastRspStats = Some(rs)
      val rd = rspStatsById.getOrElseUpdate(id, ResponderDataBuffer(new StatsBuffer, new StatsBuffer))
      updateResponderData(interval.toDuration, invokesRcvd, updatesSent)(rd)
      updateResponderData(interval.toDuration, invokesRcvd, updatesSent)(globalRspStats)

    case GetRequesterStats(id) =>
      val rd = reqStatsById.get(id).map(_.toRequesterData) getOrElse {
        RequesterData(Stats.Empty, Stats.Empty)
      }
      sender ! rd

    case GetResponderStats(id) =>
      val rd = rspStatsById.get(id).map(_.toResponderData) getOrElse {
        ResponderData(Stats.Empty, Stats.Empty)
      }
      sender ! rd

    case GetAllStats =>
      val reqData = reqStatsById.mapValues(_.toRequesterData).toMap
      val rspData = rspStatsById.mapValues(_.toResponderData).toMap
      sender ! ReqRspData(reqData, rspData)

    case GetGlobalStats =>
      sender ! GlobalData(globalReqStats.toRequesterData, globalRspStats.toResponderData)

    case GetLastStats =>
      sender ! LastStats(lastReqStats, lastRspStats)
  }

  private def updateRequesterData(duration: Duration, invokesSent: Int, updatesRcvd: Int)(rd: RequesterDataBuffer) = {
    rd.invokesSent.add(invokesSent, duration)
    rd.updatesRcvd.add(updatesRcvd, duration)
  }

  private def updateResponderData(duration: Duration, invokesRcvd: Int, updatesSent: Int)(rd: ResponderDataBuffer) = {
    rd.invokesRcvd.add(invokesRcvd, duration)
    rd.updatesSent.add(updatesSent, duration)
  }
}

/**
 * Factory for [[BenchmarkStats]] instances.
 */
object BenchmarkStats {

  /* messages */

  case class GetRequesterStats(id: String)

  case class GetResponderStats(id: String)

  case object GetAllStats

  case object GetGlobalStats

  case object GetLastStats

  /* helper classes */

  case class RequesterDataBuffer(invokesSent: StatsBuffer, updatesRcvd: StatsBuffer) {
    def toRequesterData = RequesterData(invokesSent.toStats, updatesRcvd.toStats)
  }

  case class RequesterData(invokesSent: Stats, updatesRcvd: Stats)

  case class ResponderDataBuffer(invokesRcvd: StatsBuffer, updatesSent: StatsBuffer) {
    def toResponderData = ResponderData(invokesRcvd.toStats, updatesSent.toStats)
  }

  case class ResponderData(invokesRcvd: Stats, updatesSent: Stats)

  case class ReqRspData(reqData: Map[String, RequesterData], rspData: Map[String, ResponderData])

  case class GlobalData(reqData: RequesterData, rspData: ResponderData)

  case class LastStats(reqStats: Option[RequesterStats], rspStats: Option[ResponderStats])

  /**
   * Creates a new instance of BenchmarkStats Props.
   */
  def props = Props(new BenchmarkStats)
}