package models.akka

import scala.util.control.NonFatal

import org.joda.time.DateTime

import models.{ RequestEnvelope, ResponseEnvelope }
import models.rpc._
import models.rpc.DSAValue.DSAVal
import models.metrics.EventDaos

/**
 * Handles communication with a remote DSLink in Requester mode.
 */
trait RequesterBehavior { me: AbstractDSLinkActor =>
  import models.Settings._

  protected def eventDaos: EventDaos
  
  protected def dslinkMgr: DSLinkManager
  
  // used by Close and Unsubscribe requests to retrieve the targets of previously used RID/SID
  private val targetsByRid = collection.mutable.Map.empty[Int, String]
  private val targetsBySid = collection.mutable.Map.empty[Int, PathAndQos]

  private var lastRid: Int = 0

  /**
   * Processes incoming messages from Requester DSLink and dispatches responses to it.
   */
  val requesterBehavior: Receive = {
    case m @ RequestMessage(msg, ack, requests) =>
      log.debug("{}: received {}", ownId, m)
      processRequests(requests)
      requests.lastOption foreach (req => lastRid = req.rid)
    case e @ ResponseEnvelope(responses) =>

      log.debug("{}: received {}", ownId, e)
      cleanupStoredTargets(responses)

      segregateSubscriptions(responses).map2(
        handleSubscriptions _,
        other => sendToEndpoint(ResponseEnvelope(other))
      )
  }

  /**
   * Sends Unsubscribe for all open subscriptions and Close for List commands.
   */
  def stopRequester() = {
    batchAndRoute(targetsByRid map { case (rid, target) => target -> CloseRequest(rid) })
    batchAndRoute(targetsBySid.zipWithIndex map {
      case ((sid, PathAndQos(target, _)), index) => target -> UnsubscribeRequest(lastRid + index + 1, sid)
    })
  }

  private def handleSubscriptions(subscriptions:Seq[DSAResponse]) = subscriptions.map(resp => {
    resp.updates
  })

  private def segregateSubscriptions(items:Seq[DSAResponse]):SubscriptionsAndOther = {
    val data = items.partition(isSubscription)
    SubscriptionsAndOther(data._1, data._2)
  }

  private def isSubscription(response:DSAResponse):Boolean = response match {
    case subs @ DSAResponse(0, _, _, _, _) => true
    case _ => false
  }

  /**
   * Processes and routes requests.
   */
  private def processRequests(requests: Iterable[DSARequest]) = {

    def splitRequest(request: DSARequest) = request match {
      case req: SubscribeRequest   => req.split
      case req: UnsubscribeRequest => req.split
      case req @ _                 => req :: Nil
    }

    val results = requests flatMap splitRequest flatMap (request => try {
      val target = resolveTarget(request)
      cacheRequestTarget(request, target)
      List(target -> request)
    } catch {
      case NonFatal(e) => log.error("{}: RID/SID not found for {}", ownId, request); Nil
    })

    log.debug("RID targets: {}, SID targets: {}", targetsByRid.size, targetsBySid.size)

    batchAndRoute(results)
  }

  /**
   * Removes stored targets when the response's stream is closed.
   */
  private def cleanupStoredTargets(responses: Seq[DSAResponse]) = {

    def cleanupSids(rows: Seq[DSAVal]) = try {
      rows collect extractSid foreach targetsBySid.remove
    } catch {
      case NonFatal(e) => log.error("Subscribe response does not have a valid SID")
    }

    responses filter (_.stream == Some(StreamState.Closed)) foreach {
      case DSAResponse(0, _, Some(updates), _, _)   => cleanupSids(updates)
      case DSAResponse(rid, _, _, _, _) if rid != 0 => targetsByRid.remove(rid)
    }

    log.debug("RID targets: {}, SID targets: {}", targetsByRid.size, targetsBySid.size)
  }

  /**
   * Groups the requests by their target and routes each batch as one envelope.
   */
  private def batchAndRoute(requests: Iterable[(String, DSARequest)]) = {
    requests groupBy (_._1) mapValues (_.map(_._2)) foreach {
      case (to, reqs) =>
        val envelope = RequestEnvelope(reqs.toSeq)
        log.debug("{}: sending {} to [{}]", ownId, envelope, to)
        dslinkMgr.dsaSend(to, envelope)
        logRequestBatch(to, envelope.requests)
    }
  }

  /**
   * Logs requests.
   */
  private def logRequestBatch(to: String, requests: Seq[DSARequest]) = {
    import models.Settings.Paths._

    val tgtLinkName = if (to.startsWith(Downstream) && to != Downstream)
      to drop Downstream.size + 1
    else
      "broker"

    eventDaos.requestEventDao.saveRequestBatchEvents(DateTime.now, linkName, connInfo.linkAddress,
      tgtLinkName, requests: _*)
  }

  /**
   * Saves the request's target indexed by its RID or SID, where applicable.
   */
  private def cacheRequestTarget(request: DSARequest, target: String) = request match {
    case r @ (_: ListRequest | _: InvokeRequest) => targetsByRid.put(r.rid, target)
    case r: SubscribeRequest                     => targetsBySid.put(
      r.path.sid,
      PathAndQos(target, r.path.qos.map(QoS(_)).getOrElse(QoS.Default))
    )
    case _                                       => // do nothing
  }

  /**
   * Resolves target link by analyzing the path.
   */
  private val resolveTargetByPath = {

    val extractPath: PartialFunction[DSARequest, String] = {
      case ListRequest(_, path)         => path
      case SetRequest(_, path, _, _)    => path
      case RemoveRequest(_, path)       => path
      case InvokeRequest(_, path, _, _) => path
      case SubscribeRequest(_, paths)   => paths.head.path // assuming one path after split
    }

    extractPath andThen resolveLinkPath
  }

  /**
   * Tries to resolve the request target by path or by cached RID/SID (for Close/Unsubscribe, and
   * also removes the target from the cache after the look up).
   */
  private def resolveTarget(request: DSARequest) = {

    val resolveUnsubscribeTarget: PartialFunction[DSARequest, String] = {
      case UnsubscribeRequest(_, sids) => targetsBySid.remove(sids.head).get.path
    }

    val resolveCloseTarget: PartialFunction[DSARequest, String] = {
      case CloseRequest(rid) => targetsByRid.remove(rid).get
    }

    (resolveTargetByPath orElse resolveUnsubscribeTarget orElse resolveCloseTarget)(request)
  }
}

case class SubscriptionsAndOther(subscriptions:Seq[DSAResponse], other:Seq[DSAResponse]){

  def map2(f1:Seq[DSAResponse] => Unit, f2:Seq[DSAResponse] => Unit) = {
    f1(subscriptions)
    f2(other)
  }

}

case class PathAndQos(path:String, qos:QoS.Level)

object QoS {
  sealed abstract class Level(val index:Int){
    def <(other:Level) = index < other.index
    def >(other:Level) = index > other.index
    def >=(other:Level) = index >= other.index
    def <=(other:Level) = index <= other.index
  }

  case object Default extends Level(0)
  case object Queued extends Level(1)
  case object Durable extends Level(2)
  case object DurableAndPersist extends Level(3)


  def apply(level:Int): QoS.Level = level match {
    case 0 => Default
    case 1 => Queued
    case 2 => Durable
    case 3 => DurableAndPersist
    case  _ => throw new RuntimeException("unsupported QoS level")
  }
}