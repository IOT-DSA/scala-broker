package models.akka

import scala.util.control.NonFatal

import models.{ RequestEnvelope, ResponseEnvelope }
import models.rpc._
import models.rpc.DSAValue.DSAVal

/**
 * Handles communication with a remote DSLink in Requester mode.
 */
trait RequesterBehavior { me: DSLinkActor =>

  // used by Close and Unsubscribe requests to retrieve the targets of previously used RID/SID
  private val targetsByRid = collection.mutable.Map.empty[Int, String]
  private val targetsBySid = collection.mutable.Map.empty[Int, String]

  private var lastRid: Int = 0

  /**
   * Processes incoming messages from Requester DSLink and dispatches responses to it.
   */
  val requesterBehavior: Receive = {
    case m @ RequestMessage(msg, ack, requests) =>
      log.debug(s"$ownId: received $m")
      processRequests(requests)
      requests.lastOption foreach (req => lastRid = req.rid)
    case e @ ResponseEnvelope(responses) =>
      log.debug(s"$ownId: received $e")
      processResponses(responses)
      // TODO temporary until connected/disconnected behavior is implemented
      ws foreach (_ ! e)
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
      case NonFatal(e) => log.error(s"$ownId: RID/SID not found for $request"); Nil
    })

    log.debug(s"RID targets: ${targetsByRid.size}, SID targets: ${targetsBySid.size}")

    batchAndRoute(results)
  }

  /**
   * Sends responses to Web Socket.
   */
  private def processResponses(responses: Seq[DSAResponse]) = {

    def cleanupSids(rows: Seq[DSAVal]) = try {
      rows collect extractSid foreach targetsBySid.remove
    } catch {
      case NonFatal(e) => log.error("Subscribe response does not have a valid SID")
    }

    responses filter (_.stream == Some(StreamState.Closed)) foreach {
      case DSAResponse(0, _, Some(updates), _, _)   => cleanupSids(updates)
      case DSAResponse(rid, _, _, _, _) if rid != 0 => targetsByRid.remove(rid)
    }

    log.debug(s"RID targets: ${targetsByRid.size}, SID targets: ${targetsBySid.size}")
  }

  /**
   * Groups the requests by their target and routes each batch as one envelope.
   */
  private def batchAndRoute(requests: Iterable[(String, DSARequest)]) = {
    requests groupBy (_._1) mapValues (_.map(_._2)) foreach {
      case (to, reqs) =>
        val envelope = RequestEnvelope(reqs.toSeq)
        log.debug(s"$ownId: sending $envelope to [$to]")
        dsaSend(to, envelope)
    }
  }

  /**
   * Saves the request's target indexed by its RID or SID.
   */
  private def cacheRequestTarget(request: DSARequest, target: String) = request match {
    case r @ (_: ListRequest | _: SetRequest | _: RemoveRequest | _: InvokeRequest) => targetsByRid.put(r.rid, target)
    case r: SubscribeRequest => targetsBySid.put(r.path.sid, target)
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
      case UnsubscribeRequest(_, sids) => targetsBySid.remove(sids.head).get
    }

    val resolveCloseTarget: PartialFunction[DSARequest, String] = {
      case CloseRequest(rid) => targetsByRid.remove(rid).get
    }

    (resolveTargetByPath orElse resolveUnsubscribeTarget orElse resolveCloseTarget)(request)
  }
}