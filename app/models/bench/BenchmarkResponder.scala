package models.bench

import org.joda.time.DateTime

import BenchmarkResponder.BenchmarkResponderConfig
import akka.actor.Props
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

  private val data = Array.fill(config.nodeCount)(0)
  private val subscriptions = collection.mutable.Map.empty[String, Int]

  def receive = {
    case msg @ RequestMessage(remoteMsgId, _, requests) =>
      log.debug("Endpoint[{}]: received message - {}", msg)
      requests foreach processRequest

    case msg => log.warning("[{}]: received unknown message - {}", msg)
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
      sender ! emptyResponseMessage(req.rid)
      incCounter(index.toInt)
    case r"/data(\d+)$index/resetCounter" =>
      sender ! emptyResponseMessage(req.rid)
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

  private def notifySubs(index: Int) = subscriptions.get("/data" + index) foreach { sid =>
    val update = DSAValue.obj("sid" -> sid, "value" -> data(index), "ts" -> DateTime.now.toString)
    proxy ! ResponseMessage(localMsgId.inc, None, List(DSAResponse(0, Some(StreamState.Open), Some(List(update)))))
  }

  private def emptyResponseMessage(rid: Int) =
    ResponseMessage(localMsgId.inc, None, List(DSAResponse(rid, Some(StreamState.Closed))))
}

/**
 * Factory for [[BenchmarkResponder]].
 */
object BenchmarkResponder {
  /**
   * BenchmarkResponder configuration.
   */
  case class BenchmarkResponderConfig(nodeCount: Int)

  /**
   * Creates a new instance of BenchmarkResponder props.
   */
  def props(linkName: String, proxy: CommProxy, config: BenchmarkResponderConfig) =
    Props(new BenchmarkResponder(linkName, proxy, config))
}