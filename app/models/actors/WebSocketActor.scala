package models.actors

import akka.actor.{ Actor, ActorRef, PoisonPill, Props, actorRef2Scala }
import play.api.Logger
import play.api.libs.json.{ JsObject, Json, JsValue }
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import models._
import play.api.cache.CacheApi
import scala.util.Try
import scala.util.control.NonFatal

/**
 * WebSocket actor configuration.
 */
case class WSActorConfig(out: ActorRef, dsId: String, cache: CacheApi)

/**
 * Handles a WebSocket connection.
 */
class WebSocketActor(config: WSActorConfig) extends Actor {
    import WebSocketActor._
  
    private val log = Logger(getClass)
    
    private val out = config.out
    private val cache = config.cache
    private var connInfo: ConnectionInfo = null
    
    private var localMsgId = 1
    private val targetsByRid = collection.mutable.Map.empty[Int, ActorRef]
    private val targetsBySid = collection.mutable.Map.empty[Int, ActorRef]
  
    /**
     * Sends handshake to the client.
     */
    override def preStart() = cache.get[ConnectionInfo](config.dsId) map { ci =>
        connInfo = ci
        cache.set(connInfo.linkPath, self)
        log.debug("WS actor initialized: sending 'allowed' to client")
        out ! AllowedMessage(true, 1234)
    } getOrElse {
        log.error("Cannot find the connection info for dsId=" + config.dsId)
        close
    }
  
    /**
     * Cleans up after the actor stops. 
     */
    override def postStop() = {
      if (connInfo != null)
        cache.remove(connInfo.linkPath)
      log.debug("WS actor stopped")
    }
  
    /**
     * Handles incoming message from the client.
     */
    def receive = {
      case EmptyMessage => log.debug("Empty message received, ignoring")
      case PingMessage(msg, ack) =>
        log.debug(s"Ping received with msg=$msg, acking...")
        sendAck(msg)
      case RequestMessage(msg, ack, requests) =>
        log.debug(s"WS REQUEST(msg=$msg) received: $requests")
        sendAck(msg)
        routeRequests(requests)
      case ResponseMessage(msg, ack, responses) =>
        log.debug(s"WS RESPONSE(msg=$msg) received: $responses")
        sendAck(msg)
      case ResponseEnvelope(response) =>
        log.debug(s"RSP Envelope received: $response")
        sendResponse(response)
    }
  
    /**
     * Stops the actor and closes the WS connection.
     */
    def close() = self ! PoisonPill
    
    /**
     * Sends the response message back to the client.
     */
    private def sendResponse(responses: DSAResponse*) = {
      out ! ResponseMessage(localMsgId, None, responses.toList)
      localMsgId += 1
    }
  
    /**
     * Sends an ACK back to the client.
     */
    private def sendAck(remoteMsgId: Int) = {
      out ! PingMessage(localMsgId, Some(remoteMsgId))
      localMsgId += 1
    }
    
    /**
     * Routes multiple requests
     */
    private def routeRequests(requests: List[DSARequest]) = requests flatMap splitRequest foreach routeRequest
      
    /**
     * Routes a single request to its destination.
     */
    private def routeRequest(request: DSARequest) = Try(resolveTarget(request)) map { target =>
      log.debug(s"Target found for $request: $target")
      request match {
        case r @ (_:ListRequest | _:SetRequest | _:RemoveRequest | _:InvokeRequest) => targetsByRid.put(r.rid, target)
        case r : SubscribeRequest => targetsBySid.put(r.path.sid, target)
        case _ => // do nothing
      }
      target ! RequestEnvelope(request)
    } recover {
      case NonFatal(e) => log.error(s"Target not found for $request")
    }
    
    /**
     * Some requests may have more than one destination, so we need to split them into multiple requests.
     */
    private def splitRequest(request: DSARequest) = request match {
      case req: SubscribeRequest   => req.split
      case req: UnsubscribeRequest => req.split
      case req @ _                 => req :: Nil
    }
        
    /**
     * Retrieves target ActorRef for Unsubscribe based on previously cached SID.  
     */
    private val resolveUnsubscribeTarget: PartialFunction[DSARequest, ActorRef] = {
      case UnsubscribeRequest(_, sids) => targetsBySid(sids.head)
    }
  
    /**
     * Retrieves target ActorRef for Close based on previously cached RID.  
     */
    private val resolveCloseTarget: PartialFunction[DSARequest, ActorRef] = {
      case CloseRequest(rid) => targetsByRid(rid)
    }
  
    /**
     * Extracts path component from compatible requests.
     */
    private val extractPath: PartialFunction[DSARequest, String] = {
      case ListRequest(_, path)         => path
      case SetRequest(_, path, _, _)    => path
      case RemoveRequest(_, path)       => path
      case InvokeRequest(_, path, _, _) => path
      case SubscribeRequest(_, paths)   => paths.head.path // assuming one path after split
    }
  
    /**
     * Resolves target ActorRef by analyzing the path.
     */
    private val resolveTargetByPath = extractPath andThen resolveLinkPath andThen cache.get[ActorRef] andThen (_.get)
    
    /**
     * Resolves the target link path from the request path.
     */
    private def resolveLinkPath(path: String) = path match {
      case r"/data(/.*)?$_" => "/data"
      case r"/defs(/.*)?$_" => "/defs"
      case r"/sys(/.*)?$_" => "/sys"
      case r"/users(/.*)?$_" => "/users"
      case "/downstream" => "/downstream"
      case r"/downstream/(\w+)$responder(/.*)?$_" => s"/downstream/$responder"
      case "/upstream" => "/upstream"
      case r"/upstream/(\w+)$broker(/.*)?$_" => s"/upstream/$broker"
      case _ => path
    }
    
    /**
     * Tries to resolve the request target first by path, then by cached RID or SID.
     */
    private def resolveTarget = resolveTargetByPath orElse resolveUnsubscribeTarget orElse resolveCloseTarget
}

/**
 * Factory for WebSocket actors.
 */
object WebSocketActor {
  def props(config: WSActorConfig) = Props(new WebSocketActor(config))
}