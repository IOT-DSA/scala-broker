package models.bench

import scala.concurrent.duration.{ Duration, DurationInt, FiniteDuration }

import org.joda.time.{ DateTime, Interval }

import BenchmarkResponder.{ BenchmarkResponderConfig, ResponderStats, StatsTick }
import akka.actor.{ ActorRef, Props }
import models.akka.{ CommProxy, DSLinkMode, RegexContext }
import models.rpc._

/**
 * Simulates a responder which contains:
 * - a fixed number of nodes named "data0", "data1", etc. each storing a Number value, initially 0
 * - action "incCounter" on each node, which increments the node's value
 * - action "resetCounter" on each node, which sets the node's value back to 0
 */
class BenchmarkResponder(linkName: String, proxy: CommProxy, config: BenchmarkResponderConfig)
  extends AbstractEndpointActor(linkName, DSLinkMode.Responder, proxy) {

  import context.dispatcher

  private val data = Array.fill(config.nodeCount)(0)
  private val subscriptions = collection.mutable.Map.empty[String, Int]

  private var lastReported: ResponderStats = _
  private var invokesRcvd = 0
  private var updatesSent = 0

  override def preStart() = {
    super.preStart

    lastReported = ResponderStats(linkName, new Interval(DateTime.now, DateTime.now), 0, 0)
    if (config.statsInterval > Duration.Zero)
      context.system.scheduler.schedule(config.statsInterval, config.statsInterval, self, StatsTick)
  }

  def receive = {
    case msg @ RequestMessage(remoteMsgId, _, requests) =>
      log.debug("[{}]: received {}", linkName, msg)
      requests foreach processRequest

    case StatsTick => reportStats

    case msg       => log.warning("[{}]: received unknown message - {}", linkName, msg)
  }

  private def processRequest: PartialFunction[DSARequest, Unit] = {
    case SubscribeRequest(rid, paths) =>
      paths foreach { path =>
        subscriptions += path.path -> path.sid
      }
      sender ! emptyResponseMessage(rid)

    case UnsubscribeRequest(rid, sids) =>
      val keys = subscriptions.collect {
        case (path, sid) if sids.contains(sid) => path
      }
      subscriptions --= keys
      sender ! emptyResponseMessage(rid)

    case req: InvokeRequest => processInvokeRequest(req)
  }

  private def processInvokeRequest(req: InvokeRequest) = req.path match {
    case r"/data(\d+)$index/incCounter" =>
      replyToInvoke(req)
      incCounter(index.toInt)
    case r"/data(\d+)$index/resetCounter" =>
      replyToInvoke(req)
      resetCounter(index.toInt)
  }

  private def incCounter(index: Int) = {
    data(index) += 1
    notifySubs(index)
  }

  private def resetCounter(index: Int) = {
    data(index) = 0
    notifySubs(index)
  }

  private def replyToInvoke(req: InvokeRequest) = {
    invokesRcvd += 1
    sender ! emptyResponseMessage(req.rid)
  }

  private def notifySubs(index: Int) = subscriptions.get("/data" + index) foreach { sid =>
    val update = DSAValue.obj("sid" -> sid, "value" -> data(index), "ts" -> DateTime.now.toString)
    proxy ! ResponseMessage(localMsgId.inc, None, List(DSAResponse(0, Some(StreamState.Open), Some(List(update)))))
    updatesSent += 1
  }

  private def emptyResponseMessage(rid: Int) =
    ResponseMessage(localMsgId.inc, None, List(DSAResponse(rid, Some(StreamState.Closed))))

  private def reportStats() = {
    val interval = new Interval(lastReported.interval.getEnd, DateTime.now)
    val stats = ResponderStats(linkName, interval, invokesRcvd - lastReported.invokesRcvd,
      updatesSent - lastReported.updatesSent)
    log.info("[{}]: collected {}", linkName, stats)
    config.statsCollector foreach (_ ! stats)
    lastReported = stats
  }
}

/**
 * Factory for [[BenchmarkResponder]].
 */
object BenchmarkResponder {
  /**
   * BenchmarkResponder configuration.
   */
  case class BenchmarkResponderConfig(nodeCount: Int, statsInterval: FiniteDuration = 5 seconds,
                                      statsCollector: Option[ActorRef] = None)

  /**
   * Creates a new instance of BenchmarkResponder props.
   */
  def props(linkName: String, proxy: CommProxy, config: BenchmarkResponderConfig) =
    Props(new BenchmarkResponder(linkName, proxy, config))

  /**
   * Sent by scheduler to initiate stats reporting.
   */
  case object StatsTick

  /**
   * Stats generated by the responder, sent to the stats collector.
   */
  case class ResponderStats(id: String, interval: Interval, invokesRcvd: Int, updatesSent: Int) {
    private val durationMs = interval.toDurationMillis

    val invokeRcvdPerSec = if (durationMs == 0) 0.0 else invokesRcvd * 1000.0 / durationMs
    val updateSentPerSec = if (durationMs == 0) 0.0 else updatesSent * 1000.0 / durationMs
  }
}