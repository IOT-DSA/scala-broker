package controllers

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.duration.{ DurationInt, DurationLong, FiniteDuration }
import scala.util.Random

import org.joda.time.DateTime
import org.joda.time.format.{ DateTimeFormat, PeriodFormat }

import akka.actor.{ ActorRef, ActorSystem, PoisonPill }
import akka.pattern.ask
import javax.inject.{ Inject, Singleton }
import models.akka.DSLinkManager
import models.bench.AbstractEndpointActor.{ ReqStatsBehavior, RspStatsBehavior }
import models.bench.{ BenchmarkRequester, BenchmarkResponder, BenchmarkStatsAggregator }
import models.bench.BenchmarkRequester.ReqStatsSample
import models.bench.BenchmarkResponder.RspStatsSample
import models.metrics.EventDaos
import play.api.libs.json.{ JsObject, Json, Writes }
import play.api.mvc.{ ControllerComponents, Result }

/**
 * Performs broker load test.
 */
@Singleton
class BenchmarkController @Inject() (actorSystem: ActorSystem,
                                     dslinkMgr:   DSLinkManager,
                                     eventDaos:   EventDaos,
                                     cc:          ControllerComponents) extends BasicController(cc) {

  import models.bench.BenchmarkStatsAggregator._

  private val dateFmt = DateTimeFormat.mediumTime
  private val periodFmt = PeriodFormat.getDefault

  implicit val durationWrites = Writes[org.joda.time.Duration] { duration =>
    Json.toJson(periodFmt.print(duration.toPeriod))
  }
  implicit val intervalWrites = Writes[org.joda.time.Interval] { interval =>
    Json.toJson(periodFmt.print(interval.toPeriod) + " starting at " + dateFmt.print(interval.getStart))
  }

  implicit val reqStatsSampleWrites = Writes[ReqStatsSample] { sample =>
    Json.obj("id" -> sample.id, "interval" -> sample.interval) ++ reqStats2json(sample)
  }

  implicit val rspStatsSampleWrites = Writes[RspStatsSample] { sample =>
    Json.obj("id" -> sample.id, "interval" -> sample.interval) ++ rspStats2json(sample)
  }

  implicit val reqStatsWrites = Writes[RequesterStats] { rs =>
    Json.obj("last" -> rs.lastSample, "duration" -> rs.duration) ++ reqStats2json(rs)
  }

  implicit val rspStatsWrites = Writes[ResponderStats] { rs =>
    Json.obj("last" -> rs.lastSample, "duration" -> rs.duration) ++ rspStats2json(rs)
  }

  implicit val allStatsWrites = Json.writes[AllStats]
  implicit val globalStatsWrites = Json.writes[GlobalStats]

  private val testRunning = new AtomicBoolean(false)
  def isTestRunning = testRunning.get

  private val statsInterval = 5 seconds
  private val rspNamePrefix = "BenchRSP"
  private val reqNamePrefix = "BenchREQ"

  val aggregator = actorSystem.actorOf(BenchmarkStatsAggregator.props, "benchmarkAggregator")
  private var responders: Iterable[ActorRef] = Nil
  private var requesters: Iterable[ActorRef] = Nil
  private var expectedInvokeRate: Int = _
  private var expectedUpdateToInvokeRatio: Double = _

  /**
   * Starts benchmark.
   */
  def start(reqCount: Int, rspCount: Int, rspNodeCount: Int, batchSize: Int, timeout: Long, parseJson: Boolean) = Action {
    if (isTestRunning) BadRequest("Test already running")
    else {
      aggregator ! ResetStats

      expectedInvokeRate = (reqCount * batchSize * 1000L / timeout).toInt

      responders = (1 to rspCount) map { index =>
        createBenchResponder(rspNamePrefix + index, rspNodeCount, statsInterval, parseJson)
      }

      val reqTargets = (1 to reqCount) map { index =>
        val rspIndex = Random.nextInt(rspCount) + 1
        val nodeIndex = Random.nextInt(rspNodeCount) + 1
        val path = s"/downstream/$rspNamePrefix$rspIndex/data$nodeIndex"
        val req = createBenchRequester(reqNamePrefix + index, batchSize, timeout milliseconds,
          parseJson, statsInterval, path)
        Tuple2(req, (rspIndex, nodeIndex))
      }

      requesters = reqTargets.map(_._1)

      expectedUpdateToInvokeRatio = {
        val targets = reqTargets.map(_._2)
        val expectedEvents = targets.groupBy(identity).map(_._2.size).map(a => a * a).sum
        expectedEvents * 1.0 / reqCount
      }

      testRunning.set(true)
      Ok("Test started. Check statistics at /bench/stats")
    }
  }

  /**
   * Stops benchmark.
   */
  def stop = Action {
    if (!isTestRunning) BadRequest("Test not running")
    else {
      requesters foreach (_ ! PoisonPill)
      responders foreach (_ ! PoisonPill)
      testRunning.set(false)
      Ok("Test stopped.")
    }
  }

  /**
   * Resets benchmark statistics.
   */
  def reset = Action {
    aggregator ! ResetStats
    Ok("Stats reset.")
  }

  /**
   * Displays benchmark statistics.
   */
  def viewStats = Action.async {
    val fAllStats = (aggregator ? GetAllStats).mapTo[AllStats]
    val fGlobalStats = (aggregator ? GetGlobalStats).mapTo[GlobalStats]
    for {
      allStats <- fAllStats
      globalStats <- fGlobalStats
    } yield {
      val jsAll = Json.toJson(allStats)
      val jsGlobal = Json.toJson(globalStats)
      Json.obj(
        "now" -> dateFmt.print(DateTime.now),
        "running" -> isTestRunning, "expectedInvokesSentPerSec" -> expectedInvokeRate,
        "expectedUpdateToInvokeRatio" -> expectedUpdateToInvokeRatio,
        "global" -> jsGlobal, "all" -> jsAll): Result
    }
  }

  /**
   * Creates a benchmark responder.
   */
  private def createBenchResponder(name: String, nodeCount: Int, statsInterval: FiniteDuration,
                                   parseJson: Boolean) = {
    val proxy = dslinkMgr.getCommProxy(name)
    val config = BenchmarkResponder.BenchmarkResponderConfig(nodeCount, statsInterval, parseJson, Some(aggregator))
    val props = BenchmarkResponder.props(name, proxy, eventDaos, config)
    actorSystem.actorOf(props)
  }

  /**
   * Creates a benchmark requester.
   */
  private def createBenchRequester(name: String, batchSize: Int, timeout: FiniteDuration,
                                   parseJson: Boolean, statsInterval: FiniteDuration, path: String) = {
    val proxy = dslinkMgr.getCommProxy(name)
    val config = BenchmarkRequester.BenchmarkRequesterConfig(path, batchSize, timeout, parseJson,
      statsInterval, Some(aggregator))
    val props = BenchmarkRequester.props(name, proxy, eventDaos, config)
    actorSystem.actorOf(props)
  }

  /**
   * Converts REQ stats behavior to JSON.
   */
  private def reqStats2json(rsb: ReqStatsBehavior): JsObject = Json.obj(
    "invokesSent" -> rsb.invokesSent, "invokesSentPerSec" -> rsb.invokesSentPerSec.toInt,
    "updatesRcvd" -> rsb.updatesRcvd, "updatesRcvdPerSec" -> rsb.updatesRcvdPerSec.toInt)

  /**
   * Converts RSP stats behavior to JSON.
   */
  private def rspStats2json(rsb: RspStatsBehavior): JsObject = Json.obj(
    "invokesRcvd" -> rsb.invokesRcvd, "invokesRcvdPerSec" -> rsb.invokesRcvdPerSec.toInt,
    "updatesSent" -> rsb.updatesSent, "updatesSentPerSec" -> rsb.updatesSentPerSec.toInt)
}