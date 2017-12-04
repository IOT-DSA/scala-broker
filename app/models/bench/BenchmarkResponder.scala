package models.bench

import scala.concurrent.duration.{ DurationInt, FiniteDuration }

import org.joda.time.{ DateTime, Interval }

import BenchmarkResponder.{ BenchmarkResponderConfig, RspStatsSample }
import akka.actor.{ ActorRef, Props }
import models.RequestEnvelope
import models.akka.{ CommProxy, DSLinkMode, RegexContext }
import models.rpc._
import models.metrics.MetricDao.{ requestEventDao, responseEventDao }
import net.sf.ehcache.{ Cache, CacheManager, Element }

/**
 * Simulates a responder which contains:
 * - a fixed number of nodes named "data0", "data1", etc. each storing a Number value, initially 0
 * - action "incCounter" on each node, which increments the node's value
 * - action "resetCounter" on each node, which sets the node's value back to 0
 */
class BenchmarkResponder(linkName: String, proxy: CommProxy, config: BenchmarkResponderConfig)
  extends AbstractEndpointActor(linkName, DSLinkMode.Responder, proxy, config) {

  private type Action = Function0[Seq[DSAResponse]]

  private val data = Array.fill(config.nodeCount)(0)
  private val subscriptions = collection.mutable.Map.empty[String, Int]

  private var lastReportedAt: DateTime = _
  private var invokesRcvd = 0
  private var updatesSent = 0

  private val linkAddress = "localhost"

  // TODO cache startup takes a very long time. need to investigate, and replace perhaps with Guava
  private val cacheName = linkName + "_actions"
  private val actionCache = {
    val cacheManager = CacheManager.getInstance
    cacheManager.addCache(new Cache(cacheName, config.nodeCount * 2, false, false, 0, 60))
    cacheManager.getEhcache(cacheName)
  }

  override def preStart() = {
    super.preStart

    lastReportedAt = DateTime.now
  }

  override def postStop() = {
    CacheManager.getInstance.removeCache(cacheName)

    super.postStop
  }

  override def receive = super.receive orElse {
    case msg @ RequestEnvelope(requests) =>
      log.debug("[{}]: received {}", linkName, msg)
      val responses = requests flatMap processRequest
      requestEventDao.saveRequestMessageEvent(DateTime.now, false, linkName, linkAddress,
        localMsgId.inc, requests.size)
      sendToProxy(ResponseMessage(localMsgId.inc, None, responses.toList))

    case msg => log.warning("[{}]: received unknown message - {}", linkName, msg)
  }

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

  private def processInvokeRequest(req: InvokeRequest) = {
    val action = Option(actionCache.get(req.path)).map(_.getObjectValue.asInstanceOf[Action]).getOrElse {
      val a = createAction(req.path)
      val element = new Element(req.path, a)
      actionCache.put(element)
      a
    }
    replyToInvoke(req) +: action()
  }

  private def createAction(path: String): Action = path match {
    case r"/data(\d+)$index/incCounter" => new Action {
      def apply = incCounter(index.toInt)
    }
    case r"/data(\d+)$index/resetCounter" => new Action {
      def apply = resetCounter(index.toInt)
    }
  }

  private def incCounter(index: Int) = {
    data(index - 1) += 1
    notifySubs(index)
  }

  private def resetCounter(index: Int) = {
    data(index - 1) = 0
    notifySubs(index)
  }

  private def replyToInvoke(req: InvokeRequest) = {
    invokesRcvd += 1
    emptyResponse(req.rid)
  }

  private def notifySubs(index: Int) = subscriptions.get("/data" + index) map { sid =>
    val update = DSAValue.obj("sid" -> sid, "value" -> data(index - 1), "ts" -> DateTime.now.toString)
    updatesSent += 1

    DSAResponse(0, Some(StreamState.Open), Some(List(update)))
  } toSeq

  private def emptyResponse(rid: Int) = DSAResponse(rid, Some(StreamState.Closed))

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

  protected def sendToProxy(msg: ResponseMessage) = {
    proxy ! msg
    responseEventDao.saveResponseMessageEvent(DateTime.now, true, linkName, linkAddress, msg)
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
                                      statsCollector: Option[ActorRef] = None) extends EndpointConfig

  /**
   * Creates a new instance of BenchmarkResponder props.
   */
  def props(linkName: String, proxy: CommProxy, config: BenchmarkResponderConfig) =
    Props(new BenchmarkResponder(linkName, proxy, config))

  /**
   * Stats generated by the responder, sent to the stats collector.
   */
  case class RspStatsSample(id: String, interval: Interval, invokesRcvd: Int, updatesSent: Int)
    extends RspStatsBehavior { val duration = interval.toDuration }
}