package controllers

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.duration.{ DurationInt, DurationLong, FiniteDuration }
import scala.util.Random

import org.joda.time.format.PeriodFormat

import akka.actor.{ ActorRef, ActorSystem, PoisonPill }
import akka.cluster.Cluster
import akka.pattern.ask
import akka.stream.Materializer
import akka.util.Timeout
import javax.inject.{ Inject, Singleton }
import models.akka.cluster.ClusteredDSLinkManager
import models.akka.local.LocalDSLinkManager
import models.bench.{ BenchmarkRequester, BenchmarkResponder, BenchmarkStatsAggregator }
import models.bench.AbstractEndpointActor.{ ReqStatsBehavior, RspStatsBehavior }
import play.api.libs.json.{ JsValue, Json, Writes }
import play.api.mvc.{ Action, Controller, Result }

/**
 * Performs broker load test.
 */
@Singleton
class BenchmarkController @Inject() (implicit actorSystem: ActorSystem, materializer: Materializer)
  extends Controller {
  import actorSystem.dispatcher
  import models.bench.BenchmarkStatsAggregator._

  implicit val timeout = Timeout(5 seconds)

  private val periodFmt = PeriodFormat.getDefault

  implicit val durationWrites = Writes[org.joda.time.Duration] { duration =>
    Json.toJson(periodFmt.print(duration.toPeriod))
  }
  implicit val intervalWrites = Writes[org.joda.time.Interval] { interval =>
    Json.toJson(periodFmt.print(interval.toPeriod))
  }

  implicit val reqStatsBehaviorWrites = Writes[ReqStatsBehavior] { rsb =>
    Json.obj("invokesSent" -> rsb.invokesSent, "invokesSentPerSec" -> rsb.invokesSentPerSec.toInt,
      "updatesRcvd" -> rsb.updatesRcvd, "updatesRcvdPerSec" -> rsb.updatesRcvdPerSec.toInt)
  }

  implicit val rspStatsBehaviorWrites = Writes[RspStatsBehavior] { rsb =>
    Json.obj("invokesRcvd" -> rsb.invokesRcvd, "invokesRcvdPerSec" -> rsb.invokesRcvdPerSec.toInt,
      "updatesSent" -> rsb.updatesSent, "updatesSentPerSec" -> rsb.updatesSentPerSec.toInt)
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

  val statsInterval = 5 seconds
  val rspNamePrefix = "BenchRSP"
  val reqNamePrefix = "BenchREQ"

  val aggregator = actorSystem.actorOf(BenchmarkStatsAggregator.props)
  var responders: Iterable[ActorRef] = Nil
  var requesters: Iterable[ActorRef] = Nil

  /**
   * Starts benchmark.
   */
  def start(reqCount: Int, rspCount: Int, rspNodeCount: Int, batchSize: Int, timeout: Long) = Action {
    if (isTestRunning) BadRequest("Test already running")
    else {
      aggregator ! ResetStats

      responders = (1 to rspCount) map { index =>
        createBenchResponder(rspNamePrefix + index, rspNodeCount, statsInterval)
      }

      requesters = (1 to reqCount) map { index =>
        createBenchRequester(reqNamePrefix + index, batchSize, timeout milliseconds, statsInterval, rspCount, rspNodeCount)
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
      Json.obj("running" -> isTestRunning, "global" -> jsGlobal, "all" -> jsAll): Result
    }
  }

  /**
   * Creates a benchmark responder.
   */
  private def createBenchResponder(name: String, nodeCount: Int, statsInterval: FiniteDuration) = {
    val proxy = dslinkMgr.getCommProxy(name)
    val config = BenchmarkResponder.BenchmarkResponderConfig(nodeCount, statsInterval, Some(aggregator))
    val props = BenchmarkResponder.props(name, proxy, config)
    actorSystem.actorOf(props)
  }

  /**
   * Creates a benchmark requester.
   */
  private def createBenchRequester(name: String, batchSize: Int, timeout: FiniteDuration,
                                   statsInterval: FiniteDuration, rspCount: Int, rspNodeCount: Int) = {
    val proxy = dslinkMgr.getCommProxy(name)
    val rspIndex = Random.nextInt(rspCount) + 1
    val nodeIndex = Random.nextInt(rspNodeCount) + 1
    val path = s"/downstream/$rspNamePrefix$rspIndex/data$nodeIndex"
    val config = BenchmarkRequester.BenchmarkRequesterConfig(path, batchSize, timeout,
      statsInterval, Some(aggregator))
    val props = BenchmarkRequester.props(name, proxy, config)
    actorSystem.actorOf(props)
  }

  /**
   * Converts a JSON value into a (pretty-printed) HTTP Result.
   */
  implicit protected def json2result(json: JsValue): Result = Ok(Json.prettyPrint(json)).as(JSON)
}