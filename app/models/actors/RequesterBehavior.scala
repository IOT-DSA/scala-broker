package models.actors

import scala.util.Try
import scala.util.control.NonFatal

import akka.actor.{ ActorRef, actorRef2Scala }
import models._

/**
 * Handles communication with a remote DSLink in Requester mode.
 */
trait RequesterBehavior { this: AbstractWebSocketActor =>

  // used by Close and Unsubscribe requests to retrieve the targets of previously used RID/SID
  private val targetsByRid = collection.mutable.Map.empty[Int, ActorRef]
  private val targetsBySid = collection.mutable.Map.empty[Int, ActorRef]

  /**
   * Processes incoming messages from Requester DSLink and dispatches responses to it.
   */
  val requesterBehavior: Receive = {
    case m @ RequestMessage(msg, ack, requests) =>
      log.info(s"$ownId: received $m from WebSocket")
      sendAck(msg)
      routeRequests(requests)
    case e @ ResponseEnvelope(response) =>
      log.debug(s"$ownId: received $e")
      sendResponse(response)
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
  private def routeRequest(request: DSARequest) = Try(resolveTarget(request)) map { target =>
    cacheRequestTarget(request, target)
    log.debug(s"$ownId: routing $request to $target")
    target ! RequestEnvelope(request)
  } recover {
    case NonFatal(e) => log.error(s"$ownId: target not found for $request")
  }

  /**
   * Saves the request's target actor indexed by its RID or SID.
   */
  private def cacheRequestTarget(request: DSARequest, target: ActorRef) = request match {
    case r @ (_: ListRequest | _: SetRequest | _: RemoveRequest | _: InvokeRequest) => targetsByRid.put(r.rid, target)
    case r: SubscribeRequest => targetsBySid.put(r.path.sid, target)
    case _ => // do nothing
  }

  /**
   * Resolves target ActorRef by analyzing the path.
   */
  private val resolveTargetByPath = {

    val extractPath: PartialFunction[DSARequest, String] = {
      case ListRequest(_, path)         => path
      case SetRequest(_, path, _, _)    => path
      case RemoveRequest(_, path)       => path
      case InvokeRequest(_, path, _, _) => path
      case SubscribeRequest(_, paths)   => paths.head.path // assuming one path after split
    }

    extractPath andThen resolveLinkPath andThen cache.get[ActorRef] andThen (_.get)
  }

  /**
   * Tries to resolve the request target by path or by cached RID/SID (for Close/Unsubscribe, and
   * also removes the target from the cache after the look up).
   */
  private def resolveTarget(request: DSARequest) = {

    val resolveUnsubscribeTarget: PartialFunction[DSARequest, ActorRef] = {
      case UnsubscribeRequest(_, sids) => targetsBySid.remove(sids.head).get
    }

    val resolveCloseTarget: PartialFunction[DSARequest, ActorRef] = {
      case CloseRequest(rid) => targetsByRid.remove(rid).get
    }

    (resolveTargetByPath orElse resolveUnsubscribeTarget orElse resolveCloseTarget)(request)
  }

  /**
   * Resolves the target link path from the request path.
   */
  def resolveLinkPath(path: String) = path match {
    case r"/data(/.*)?$_"                       => settings.Paths.Data
    case r"/defs(/.*)?$_"                       => settings.Paths.Defs
    case r"/sys(/.*)?$_"                        => settings.Paths.Sys
    case r"/users(/.*)?$_"                      => settings.Paths.Users
    case "/downstream"                          => settings.Paths.Downstream
    case r"/downstream/(\w+)$responder(/.*)?$_" => s"/downstream/$responder"
    case "/upstream"                            => settings.Paths.Upstream
    case r"/upstream/(\w+)$broker(/.*)?$_"      => s"/upstream/$broker"
    case _                                      => path
  }
}