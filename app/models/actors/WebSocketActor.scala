package models.actors

import akka.actor.{ Actor, ActorRef, PoisonPill, Props, actorRef2Scala }
import play.api.Logger
import play.api.libs.json.{ JsObject, Json, JsValue }
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import models._

/**
 * Handles a WebSocket connection.
 */
class WebSocketActor(out: ActorRef) extends Actor {
  private val log = Logger(getClass)
  private var localMsgId = 1

  override def preStart() = {
    out ! AllowedMessage(true, 1234)
    log.debug("WS actor initialized: sending 'allowed' to client")
  }

  def receive = {
    case EmptyMessage => log.debug("Empty message received, ignoring")
    case PingMessage(msg, ack) =>
      log.debug(s"Ping received with msg=$msg, acking...")
      sendAck(msg)
    case RequestMessage(msg, ack, requests) =>
      log.debug(s"REQUEST(msg=$msg) received: $requests")
      sendAck(msg)
    case ResponseMessage(msg, ack, responses) =>
      log.debug(s"RESPONSE(msg=$msg) received: $responses")
      sendAck(msg)
  }

  def close() = self ! PoisonPill

  private def sendAck(remoteMsgId: Int) = {
    out ! PingMessage(localMsgId, Some(remoteMsgId))
    localMsgId += 1
  }
}

/**
 * Factory for WebSocket actors.
 */
object WebSocketActor {
  def props(out: ActorRef) = Props(new WebSocketActor(out))
}