package models.bench

import scala.concurrent.duration.{ DurationInt, FiniteDuration }

import org.joda.time.{ DateTime, Interval }

import BenchmarkResponder.{ BenchmarkResponderConfig, RspStatsSample }
import akka.actor.{ ActorRef, Props }
import akka.routing.Routee
import models.RequestEnvelope
import models.akka.{ DSLinkMode, RegexContext, RichRoutee }
import models.metrics.EventDaos
import models.rpc._
import models.util.SimpleCache

/**
 * Simulates a responder which contains:
 * - a fixed number of nodes named "data0", "data1", etc. each storing a Number value, initially 0
 * - action "incCounter" on each node, which increments the node's value
 * - action "resetCounter" on each node, which sets the node's value back to 0
 */
class BenchmarkResponder(linkName: String, routee: Routee, eventDaos: EventDaos, config: BenchmarkResponderConfig)
  extends AbstractEndpointActor(linkName, DSLinkMode.Responder, routee, config) {

  import eventDaos._

  private type Action = Function0[Seq[DSAResponse]]

  private val data = Array.fill(config.nodeCount)(0)
  private val subscriptions = collection.mutable.Map.empty[String, Int]

  private var lastReportedAt: DateTime = _
  private var invokesRcvd: Int = 0
  private var updatesSent: Int = 0

  private val linkAddress = "localhost"

  private val actionCache = new SimpleCache[String, Action](100, 1)

  /**
   * Marks the start time.
   */
  override def preStart() = {
    super.preStart

    lastReportedAt = DateTime.now
  }

  /**
   * Event loop.
   */
  override def receive = super.receive orElse {
    case env: RequestEnvelope =>
      val requests = viaJson(env).requests
      log.debug("[{}]: received {}", linkName, env)
      val responses = requests flatMap processRequest
      requestEventDao.saveRequestMessageEvent(DateTime.now, false, linkName, linkAddress,
        localMsgId.inc, requests.size)
      sendToProxy(ResponseMessage(localMsgId.inc, None, responses.toList))

    case msg => log.warning("[{}]: received unknown message - {}", linkName, msg)
  }

  /**
   * Processes the incoming request and returns a list of responses to deliver.
   */
  private def processRequest: PartialFunction[DSARequest, Seq[DSAResponse]] = {
    case SubscribeRequest(rid, paths) =>
      paths foreach { path =>
        subscriptions += path.path -> path.sid
      }
      List(emptyResponse(rid))

    case UnsubscribeRequest(rid, sids) =>
      val keys = subscriptions.collect {
        case (path, sid) if sids.contains(sid) => path
      }
      subscriptions --= keys
      List(emptyResponse(rid))

    case req: InvokeRequest => processInvokeRequest(req)
  }

  /**
   * Processes an INVOKE request.
   */
  private def processInvokeRequest(req: InvokeRequest) = {
    val action = actionCache.getOrElseUpdate(req.path, createAction(req.path))
    replyToInvoke(req) +: action()
  }

  /**
   * Creates either `incCounter` or `resetCounter` action, depending on the path.
   */
  private def createAction(path: String): Action = path match {
    case r"/data(\d+)$index/incCounter" => new Action {
      def apply = incCounter(index.toInt)
    }
    case r"/data(\d+)$index/resetCounter" => new Action {
      def apply = resetCounter(index.toInt)
    }
  }

  /**
   * Increments the value of the specified node.
   */
  private def incCounter(index: Int) = {
    data(index - 1) += 1
    notifySubs(index)
  }

  /**
   * Resets the value of the specified node to 0.
   */
  private def resetCounter(index: Int) = {
    data(index - 1) = 0
    notifySubs(index)
  }

  private def replyToInvoke(req: InvokeRequest) = {
    invokesRcvd += 1
    updatesSent += 1
    emptyResponse(req.rid)
  }

  /**
   * Generates a response to deliver to the node subscribers.
   */
  private def notifySubs(index: Int) = subscriptions.get("/data" + index) map { sid =>
    val update = DSAValue.obj("sid" -> sid, "value" -> data(index - 1), "ts" -> DateTime.now.toString)
    updatesSent += 1

    DSAResponse(0, Some(StreamState.Open), Some(List(update)))
  } toSeq

  /**
   * Creates an empty DSA response with the specified RID.
   */
  private def emptyResponse(rid: Int) = DSAResponse(rid, Some(StreamState.Closed))

  /**
   * Sends the statistics to the aggregator.
   */
  protected def reportStats() = {
    val now = DateTime.now
    val interval = new Interval(lastReportedAt, now)
    val stats = RspStatsSample(linkName, interval, invokesRcvd, updatesSent)
    log.debug("[{}]: collected {}", linkName, stats)
    config.statsCollector foreach (_ ! stats)
    lastReportedAt = now
    invokesRcvd = 0
    updatesSent = 0
  }

  /**
   * Sends response message to the DSLink actor.
   */
  protected def sendToProxy(msg: ResponseMessage) = {
    val message = viaJson[ResponseMessage, DSAMessage](msg)
    routee ! message
    responseEventDao.saveResponseMessageEvent(DateTime.now, true, linkName, linkAddress, message)
  }
}

/**
 * Factory for [[BenchmarkResponder]].
 */
object BenchmarkResponder {
  import AbstractEndpointActor._

  /**
   * BenchmarkResponder configuration.
   */
  case class BenchmarkResponderConfig(nodeCount: Int, statsInterval: FiniteDuration = 5 seconds,
                                      parseJson:      Boolean,
                                      statsCollector: Option[ActorRef] = None) extends EndpointConfig

  /**
   * Creates a new instance of BenchmarkResponder props.
   */
  def props(linkName: String, routee: Routee, eventDaos: EventDaos, config: BenchmarkResponderConfig) =
    Props(new BenchmarkResponder(linkName, routee, eventDaos, config))

  /**
   * Stats generated by the responder, sent to the stats collector.
   */
  case class RspStatsSample(id: String, interval: Interval, invokesRcvd: Int, updatesSent: Int)
    extends RspStatsBehavior { val duration = interval.toDuration }
}