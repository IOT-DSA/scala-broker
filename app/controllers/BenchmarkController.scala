package controllers

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.duration.{ DurationInt, DurationLong, FiniteDuration }
import scala.util.Random

import org.joda.time.DateTime
import org.joda.time.format.{ DateTimeFormat, PeriodFormat }

import akka.actor.{ ActorRef, ActorSystem, PoisonPill }
import akka.cluster.Cluster
import akka.pattern.ask
import akka.stream.Materializer
import akka.util.Timeout
import javax.inject.{ Inject, Singleton }
import models.akka.cluster.ClusteredDSLinkManager
import models.akka.local.LocalDSLinkManager
import models.bench.AbstractEndpointActor.{ ReqStatsBehavior, RspStatsBehavior }
import models.bench.{ BenchmarkRequester, BenchmarkResponder }
import models.bench.BenchmarkRequester.ReqStatsSample
import models.bench.BenchmarkResponder.RspStatsSample
import models.bench.BenchmarkStatsAggregator
import play.api.libs.json.{ JsObject, JsValue, Json, Writes }
import play.api.mvc.{ Action, Controller, Result }

/**
 * Performs broker load test.
 */
@Singleton
class BenchmarkController @Inject() (implicit actorSystem: ActorSystem) extends Controller {
  import actorSystem.dispatcher
  import BenchmarkStatsAggregator._

  implicit val timeout = Timeout(5 seconds)

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

  val isClusterMode = actorSystem.hasExtension(Cluster)

  val dslinkMgr = if (isClusterMode)
    new ClusteredDSLinkManager(true)
  else
    new LocalDSLinkManager

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
    val props = BenchmarkResponder.props(name, proxy, config)
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
    val props = BenchmarkRequester.props(name, proxy, config)
    actorSystem.actorOf(props)
  }

  /**
   * Converts a JSON value into a (pretty-printed) HTTP Result.
   */
  implicit protected def json2result(json: JsValue): Result = Ok(Json.prettyPrint(json)).as(JSON)

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