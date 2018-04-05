package models.bench

import scala.concurrent.Future
import scala.concurrent.duration.{ DurationLong, FiniteDuration }
import scala.util.Random

import BenchmarkActor.{ CreateAndStartRequesters, CreateResponders, ReqNamePrefix, RequesterTarget, RequestersStarted, RespondersReady, RspNamePrefix, StopAll }
import BenchmarkRequester.BenchmarkRequesterConfig
import BenchmarkResponder.BenchmarkResponderConfig
import akka.actor.{ Actor, ActorLogging, ActorRef, PoisonPill, Props }
import akka.pattern.{ ask, pipe }
import akka.routing.Routee
import akka.util.Timeout
import models.Settings
import models.akka.Messages.GetOrCreateDSLink
import models.metrics.EventDaos

/**
 * Coordinates broker benchmarks.
 */
class BenchmarkActor(eventDaos: EventDaos) extends Actor with ActorLogging {

  import context.dispatcher

  implicit val timeout = Timeout(Settings.QueryTimeout)

  val downstream = context.actorSelection("/user/downstream")

  var responders: Iterable[ActorRef] = Nil
  var requesters: Iterable[ActorRef] = Nil

  def receive = idle

  /**
   * Message loop when idle.
   */
  def idle: Receive = {
    case CreateResponders(rspIndices, rspNodeCount, statsInterval, collector, parseJson) =>
      val fRefs = Future.sequence(rspIndices map { index =>
        val name = RspNamePrefix + index
        createResponder(name, rspNodeCount, statsInterval, collector, parseJson)
      })
      fRefs map { refs =>
        log.info("{} responders created", refs.size)
        this.responders = refs
        RespondersReady(refs)
      } pipeTo sender

    case CreateAndStartRequesters(subscribe, reqIndices, rspCount, rspNodeCount, batchSize, timeout,
      statsInterval, collector, parseJson) =>
      val fTuples = Future.sequence(reqIndices map { index =>
        val name = ReqNamePrefix + index
        val rspIndex = Random.nextInt(rspCount) + 1
        val nodeIndex = Random.nextInt(rspNodeCount) + 1
        val path = s"/downstream/$RspNamePrefix$rspIndex/data$nodeIndex"
        createRequester(
          name, subscribe, batchSize, timeout milliseconds, statsInterval, path, collector, parseJson) map {
          Tuple2(_, RequesterTarget(rspIndex, nodeIndex))
        }
      })
      fTuples map { tuples =>
        log.info("{} requesters created", tuples.size)
        this.requesters = tuples.map(_._1)
        RequestersStarted(tuples.toMap)
      } pipeTo sender
      context.become(running)

    case StopAll =>
      stopAll
  }

  /**
   * Message loop when running the benchmark.
   */
  def running: Receive = {
    case StopAll => stopAll
  }

  /**
   * Stops and kills all requesters and responders.
   */
  private def stopAll() = {
    requesters foreach (_ ! PoisonPill)
    responders foreach (_ ! PoisonPill)
    requesters = Nil
    responders = Nil
    log.info("{} requesters and {} responders stopped and removed", requesters.size, responders.size)
    context.become(idle)
  }

  /**
   * Creates a benchmark responder with the given name, number of nodes, stats reporting interval and
   * json flag.
   */
  private def createResponder(name:          String,
                              nodeCount:     Int,
                              statsInterval: FiniteDuration,
                              collector:     Option[ActorRef],
                              parseJson:     Boolean): Future[ActorRef] = getDSLink(name) map { routee =>
    val config = BenchmarkResponderConfig(nodeCount, statsInterval, parseJson, collector)
    context.system.actorOf(BenchmarkResponder.props(name, routee, eventDaos, config))
  }

  /**
   * Creates a benchmark requester with the given name, subscription mode, batch size, batch timtout,
   * stats reporting interval, subscription path and json flag.
   */
  private def createRequester(name:          String,
                              subscribe:     Boolean,
                              batchSize:     Int,
                              tout:          FiniteDuration,
                              statsInterval: FiniteDuration,
                              path:          String,
                              collector:     Option[ActorRef],
                              parseJson:     Boolean): Future[ActorRef] = getDSLink(name) map { routee =>
    val config = BenchmarkRequesterConfig(subscribe, path, batchSize, tout, parseJson, statsInterval, collector)
    context.system.actorOf(BenchmarkRequester.props(name, routee, eventDaos, config))
  }

  /**
   * Returns a routee for the specified DSLink.
   */
  private def getDSLink(name: String) = (downstream ? GetOrCreateDSLink(name)).mapTo[Routee]
}

/**
 * Factory for [[BenchmarkActor]] instances.
 */
object BenchmarkActor {

  /**
   * Benchmark Requester name prefix.
   */
  val RspNamePrefix = "BenchRSP"

  /**
   * Benchmark Responder name prefix.
   */
  val ReqNamePrefix = "BenchREQ"

  /**
   * A selected responder index and responder's node index selected as some requester's target.
   */
  case class RequesterTarget(rspIndex: Int, nodeIndex: Int)

  /* messages */

  /**
   * Commands to create a set of benchmark responders with the specified indices.
   */
  case class CreateResponders(rspIndices:    Seq[Int],
                              nodeCount:     Int,
                              statsInterval: FiniteDuration,
                              collector:     Option[ActorRef],
                              parseJson:     Boolean)

  /**
   * Notification sent back by the actor that responders have been created.
   */
  case class RespondersReady(responders: Iterable[ActorRef])

  /**
   * Commands to create and start a set of benchmark requesters with the specified indices.
   */
  case class CreateAndStartRequesters(subscribe:     Boolean,
                                      reqIndices:    Seq[Int],
                                      rspCount:      Int,
                                      rspNodeCount:  Int,
                                      batchSize:     Int,
                                      timeout:       Long,
                                      statsInterval: FiniteDuration,
                                      collector:     Option[ActorRef],
                                      parseJson:     Boolean)

  /**
   * Notification sent back by the actor that requesters have been created and started.
   */
  case class RequestersStarted(requesters: Map[ActorRef, RequesterTarget])

  /**
   * Commands to stop and remove all benchmark endpoint actors.
   */
  case object StopAll

  /**
   * Creates a new instance of [[BenchmarkActor]] props.
   */
  def props(eventDaos: EventDaos) = Props(new BenchmarkActor(eventDaos))
}