package models.akka

import scala.util.control.NonFatal
import org.joda.time.DateTime
import models.{RequestEnvelope, ResponseEnvelope}
import models.rpc._
import models.rpc.DSAValue.DSAVal
import models.metrics.EventDaos

/**
 * Handles communication with a remote DSLink in Requester mode.
 */
trait RequesterBehavior { me: AbstractDSLinkActor =>

  protected def eventDaos: EventDaos
  protected def dslinkMgr: DSLinkManager
  
  // used by Close and Unsubscribe requests to retrieve the targets of previously used RID/SID
  private val targetsByRid = collection.mutable.Map.empty[Int, String]
  private val targetsBySid = collection.mutable.Map.empty[Int, String]

  // figure out should we persist this as a part of the internal state
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
      sendToEndpoint(e)
  }

  /**
    * Recovers events of requester behavior from the journal.
    */
  val recoverRequesterState: Receive = {
    case event: RidTargetsRequesterState =>
      log.debug("{}: trying to recover {}", ownId, event)
      targetsByRid.put(event.rid, event.target)
    case event: SidTargetsRequesterState =>
      log.debug("{}: trying to recover {}", ownId, event)
      targetsBySid.put(event.sid, event.target)
    case event: RemoveTargetByRid =>
      log.debug("{}: trying to recover remove action by {}", ownId, event)
      targetsByRid.remove(event.rid)
    case event: RemoveTargetBySid =>
      log.debug("{}: trying to recover remove action by {}", ownId, event)
      targetsBySid.remove(event.sid)
  }

  /**
   * Sends Unsubscribe for all open subscriptions and Close for List commands.
   */
  def stopRequester() = {
    batchAndRoute(targetsByRid map { case (rid, target) => target -> CloseRequest(rid) })
    batchAndRoute(targetsBySid.zipWithIndex map {
      case ((sid, target), index) => target -> UnsubscribeRequest(lastRid + index + 1, sid)
    })
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
      case NonFatal(_) => log.error("{}: RID/SID not found for {}", ownId, request); Nil
    })

    log.debug("{}: RID targets: {}, SID targets: {}", ownId, targetsByRid.size, targetsBySid.size)

    batchAndRoute(results)
  }

  /**
   * Removes stored targets when the response's stream is closed.
   */
  private def cleanupStoredTargets(responses: Seq[DSAResponse]) = {

    def cleanupSids(rows: Seq[DSAVal]) = try {
        for (r <- rows)
          persist(RemoveTargetBySid(extractSid(r))) { event =>
            log.debug("{}: removing by SID persisted {}", ownId, event)
            targetsBySid.remove(event.sid)
          }
    } catch {
      case NonFatal(_) => log.error("{}: subscribe response does not have a valid SID", ownId)
    }

    responses filter (_.stream == Some(StreamState.Closed)) foreach {
      case DSAResponse(0, _, Some(updates), _, _)   => cleanupSids(updates)
      case DSAResponse(rid, _, _, _, _) if rid != 0 =>
        persist(RemoveTargetByRid(rid)) { event =>
          log.debug("{}: removing by RID persisted {}", ownId, event)
          targetsByRid.remove(event.rid)
        }
    }

    log.debug("{}: RID targets: {}, SID targets: {}", ownId, targetsByRid.size, targetsBySid.size)
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
    case r @ (_: ListRequest | _: InvokeRequest) =>
      persist(RidTargetsRequesterState(r.rid, target)) { event =>
        log.debug("{}: RID targets persisted {}", ownId, event)
        targetsByRid.put(event.rid, event.target)
      }
    case r: SubscribeRequest =>
      persist(SidTargetsRequesterState(r.path.sid, target)) { event =>
        log.debug("{}: SID targets persisted {}", ownId, event)
        targetsBySid.put(event.sid, event.target)
      }
    case _ => // do nothing
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
      case UnsubscribeRequest(_, sids) =>
        val target = targetsBySid.get(sids.head).get
        persist(RemoveTargetBySid(sids.head)) { event =>
          log.debug("{}: removing by SID persisted {}", ownId, event)
          targetsBySid.remove(event.sid)
        }
        target
    }

    val resolveCloseTarget: PartialFunction[DSARequest, String] = {
      case CloseRequest(rid) =>
        val target = targetsByRid.get(rid).get
        persist(RemoveTargetByRid(rid)) { event =>
          log.debug("{}: removing by RID persisted {}", ownId, event)
          targetsByRid.remove(rid)
        }
        target
    }

    (resolveTargetByPath orElse resolveUnsubscribeTarget orElse resolveCloseTarget)(request)
  }
}