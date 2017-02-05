package models.actors

import scala.util.control.NonFatal

import models.{ MessageRouter, ResponseEnvelope }
import models.rpc._

/**
 * Handles communication with a remote DSLink in Requester mode.
 */
trait RequesterBehavior { this: AbstractWebSocketActor =>

  // for request routing
  def router: MessageRouter

  // used by Close and Unsubscribe requests to retrieve the targets of previously used RID/SID
  private val targetsByRid = collection.mutable.Map.empty[Int, String]
  private val targetsBySid = collection.mutable.Map.empty[Int, String]

  private val resolveLink = resolveLinkPath(settings) _

  /**
   * Processes incoming messages from Requester DSLink and dispatches responses to it.
   */
  val requesterBehavior: Receive = {
    case m @ RequestMessage(msg, ack, requests) =>
      log.info(s"$ownId: received $m from WebSocket")
      sendAck(msg)
      routeRequests(requests)
    case e @ ResponseEnvelope(from, to, responses) =>
      log.debug(s"$ownId: received $e")
      sendResponses(responses: _*)
  }

  /**
   * Routes multiple requests.
   */
  private def routeRequests(requests: Iterable[DSARequest]) = {

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

    results groupBy (_._1) mapValues (_.map(_._2)) foreach {
      case (to, reqs) => router.routeRequests(connInfo.linkPath, to, false, reqs.toSeq: _*) recover {
        case NonFatal(e) => log.error(s"$ownId: error routing the requests {}", e)
      }
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

    extractPath andThen resolveLink
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