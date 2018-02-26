package controllers

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.Await
import scala.concurrent.duration.{ Duration, DurationInt, DurationLong, FiniteDuration }
import scala.util.Random

import org.joda.time.DateTime

import akka.actor.{ ActorRef, ActorSystem, PoisonPill }
import akka.pattern.ask
import akka.routing.Routee
import javax.inject.{ Inject, Singleton }
import models.akka.{ BrokerActors, DSLinkManager }
import models.akka.Messages.GetOrCreateDSLink
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
                                     actors:      BrokerActors,
                                     eventDaos:   EventDaos,
                                     cc:          ControllerComponents) extends BasicController(cc) {

  import models.bench.BenchmarkStatsAggregator._

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

  private val downstream = actors.downstream

  /**
   * Starts benchmark.
   */
  def start(subscribe: Boolean, reqCount: Int, rspCount: Int, rspNodeCount: Int,
            batchSize: Int, timeout: Long,
            parseJson: Boolean) = Action {

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
        val req = createBenchRequester(subscribe, reqNamePrefix + index, batchSize, timeout milliseconds,
          statsInterval, path, parseJson)
        Tuple2(req, (rspIndex, nodeIndex))
      }

      requesters = reqTargets.map(_._1)

      expectedUpdateToInvokeRatio = if (subscribe) {
        val targets = reqTargets.map(_._2)
        val expectedEvents = targets.groupBy(identity).map(_._2.size).map(a => a * a).sum
        (expectedEvents + reqCount).toDouble / reqCount
      } else 1.0

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
        "now" -> DateFmt.print(DateTime.now),
        "running" -> isTestRunning,
        "configuration" -> Json.obj(
          "requesters" -> requesters.size,
          "responders" -> responders.size,
          "expectedInvokesSentPerSec" -> expectedInvokeRate,
          "expectedUpdateToInvokeRatio" -> expectedUpdateToInvokeRatio,
          "expectedUpdatesRcvdPerSec" -> (expectedInvokeRate * expectedUpdateToInvokeRatio).toInt),
        "global" -> jsGlobal,
        "all" -> jsAll): Result
    }
  }

  /**
   * Creates a benchmark responder.
   * TODO refactor to remove blocking
   */
  private def createBenchResponder(name: String, nodeCount: Int, statsInterval: FiniteDuration,
                                   parseJson: Boolean) = {
    val config = BenchmarkResponder.BenchmarkResponderConfig(nodeCount, statsInterval, parseJson, Some(aggregator))
    val routee = (downstream ? GetOrCreateDSLink(name)).mapTo[Routee]
    val ref = routee map (r => actorSystem.actorOf(BenchmarkResponder.props(name, r, eventDaos, config)))
    Await.result(ref, Duration.Inf)
  }

  /**
   * Creates a benchmark requester.
   * TODO refactor to remove blocking
   */
  private def createBenchRequester(subscribe: Boolean, name: String, batchSize: Int, tout: FiniteDuration,
                                   statsInterval: FiniteDuration, path: String,
                                   parseJson: Boolean) = {
    val config = BenchmarkRequester.BenchmarkRequesterConfig(subscribe, path, batchSize, tout,
      parseJson, statsInterval, Some(aggregator))
    val routee = (downstream ? GetOrCreateDSLink(name)).mapTo[Routee]
    val ref = routee map (r => actorSystem.actorOf(BenchmarkRequester.props(name, r, eventDaos, config)))
    Await.result(ref, Duration.Inf)
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