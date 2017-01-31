package models.actors

import scala.util.Try
import scala.util.control.NonFatal

import models.{ MessageRouter, ResponseEnvelope }
import models.rpc._

/**
 * Handles communication with a remote DSLink in Requester mode.
 */
trait RequesterBehavior { this: AbstractWebSocketActor =>
  import settings._
  
  // for request routing
  def router: MessageRouter

  // used by Close and Unsubscribe requests to retrieve the targets of previously used RID/SID
  private val targetsByRid = collection.mutable.Map.empty[Int, String]
  private val targetsBySid = collection.mutable.Map.empty[Int, String]

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

    requests flatMap splitRequest foreach routeRequest
  }

  /**
   * Routes a single request to its destination.
   */
  private def routeRequest(request: DSARequest) = resolveTarget(request) flatMap { target =>
    cacheRequestTarget(request, target)
    log.debug(s"$ownId: routing $request to [$target]")
    router.routeRequests(connInfo.linkPath, target, request)
  } recover {
    case e: NoSuchElementException => log.error(s"$ownId: RID/SID not found for $request")
    case NonFatal(e) => log.error(s"$ownId: cannot route $request: {}", e)
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
  private def resolveTarget(request: DSARequest): Try[String] = Try {

    val resolveUnsubscribeTarget: PartialFunction[DSARequest, String] = {
      case UnsubscribeRequest(_, sids) => targetsBySid.remove(sids.head).get
    }

    val resolveCloseTarget: PartialFunction[DSARequest, String] = {
      case CloseRequest(rid) => targetsByRid.remove(rid).get
    }

    (resolveTargetByPath orElse resolveUnsubscribeTarget orElse resolveCloseTarget)(request)
  }

  /**
   * Resolves the target link path from the request path.
   */
  def resolveLinkPath(path: String) = path match {
    case r"/data(/.*)?$_"                       => Paths.Data
    case r"/defs(/.*)?$_"                       => Paths.Defs
    case r"/sys(/.*)?$_"                        => Paths.Sys
    case r"/users(/.*)?$_"                      => Paths.Users
    case "/downstream"                          => Paths.Downstream
    case r"/downstream/(\w+)$responder(/.*)?$_" => s"/downstream/$responder"
    case "/upstream"                            => Paths.Upstream
    case r"/upstream/(\w+)$broker(/.*)?$_"      => s"/upstream/$broker"
    case _                                      => path
  }
}