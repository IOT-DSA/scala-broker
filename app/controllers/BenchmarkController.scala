package controllers

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import org.joda.time.DateTime

import akka.actor.{ ActorSystem, RootActorPath }
import akka.cluster.Cluster
import akka.pattern.ask
import akka.routing.{ ActorRefRoutee, ActorSelectionRoutee, Routee }
import javax.inject.{ Inject, Singleton }
import models.akka.{ BrokerActors, DSLinkManager, RichRoutee }
import models.bench.AbstractEndpointActor.{ ReqStatsBehavior, RspStatsBehavior }
import models.bench.BenchmarkRequester.ReqStatsSample
import models.bench.BenchmarkResponder.RspStatsSample
import models.bench.BenchmarkStatsAggregator
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

  import models.bench.BenchmarkActor._
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

  val isClusterMode = actorSystem.hasExtension(Cluster)
  val cluster = if (isClusterMode) Some(Cluster(actorSystem)) else None

  private val statsInterval = 5 seconds

  val aggregator = actorSystem.actorOf(BenchmarkStatsAggregator.props, "benchmarkAggregator")
  private var rspCount: Int = _
  private var reqCount: Int = _
  private var expectedInvokeRate: Int = _
  private var expectedUpdateToInvokeRatio: Double = _

  /**
   * Starts benchmark.
   */
  def start(subscribe: Boolean, reqCount: Int, rspCount: Int, rspNodeCount: Int,
            batchSize: Int, batchTimeout: Long,
            parseJson: Boolean) = Action {

    if (isTestRunning) BadRequest("Test already running")
    else {
      aggregator ! ResetStats

      this.reqCount = reqCount
      this.rspCount = rspCount
      expectedInvokeRate = (reqCount * batchSize * 1000L / batchTimeout).toInt

      val rspChunks = 1 to rspCount groupBy (_ % routees.size) values

      val fRspReady = Future.sequence(routees zip rspChunks map {
        case (routee, rspIndices) => createResponders(routee, rspIndices, rspNodeCount, parseJson)
      })

      val fReqStarted = fRspReady flatMap { _ =>
        val reqChunks = 1 to reqCount groupBy (_ % routees.size) values

        Future.sequence(routees zip reqChunks map {
          case (routee, reqIndices) => createRequesters(routee, subscribe, reqIndices, rspCount, rspNodeCount,
            batchSize, batchTimeout, parseJson)
        })
      }

      fReqStarted foreach { rs =>
        expectedUpdateToInvokeRatio = if (subscribe) {
          val targets = rs.flatMap(_.requesters).map(_._2)
          val expectedEvents = targets.groupBy(identity).map(_._2.size).map(a => a * a).sum
          (expectedEvents + reqCount).toDouble / reqCount
        } else 1.0
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
      routees foreach (_ ! StopAll)
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
          "requesters" -> reqCount,
          "responders" -> rspCount,
          "expectedInvokesSentPerSec" -> expectedInvokeRate,
          "expectedUpdateToInvokeRatio" -> expectedUpdateToInvokeRatio,
          "expectedUpdatesRcvdPerSec" -> (expectedInvokeRate * expectedUpdateToInvokeRatio).toInt),
        "global" -> jsGlobal,
        "all" -> jsAll): Result
    }
  }

  /**
   * Returns a list of routees for benchmark actors. It will be a list with a single element for
   * a standalone broker and one or more elements for a clustered broker.
   */
  private def routees: Seq[Routee] = cluster.map(_.state.members.map { member =>
    ActorSelectionRoutee(actorSystem.actorSelection(RootActorPath(member.address) / "user" / "benchmark"))
  }.toList).getOrElse(List(ActorRefRoutee(actors.benchmark)))

  /**
   * Asks a benchmark actor to create a set of responders.
   */
  private def createResponders(routee: Routee, rspIndices: Seq[Int], rspNodeCount: Int, parseJson: Boolean) = {
    val message = CreateResponders(rspIndices, rspNodeCount, statsInterval, Some(aggregator), parseJson)
    (routee ? message).mapTo[RespondersReady]
  }

  /**
   * Asks a benchmark actor to create and start a set of requesters.
   */
  private def createRequesters(routee: Routee, subscribe: Boolean, reqIndices: Seq[Int],
                               rspCount: Int, rspNodeCount: Int,
                               batchSize: Int, batchTimeout: Long, parseJson: Boolean) = {
    val message = CreateAndStartRequesters(subscribe, reqIndices, rspCount, rspNodeCount,
      batchSize, batchTimeout, statsInterval, Some(aggregator), parseJson)
    (routee ? message).mapTo[RequestersStarted]
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