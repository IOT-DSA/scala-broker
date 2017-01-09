package models.actors

import akka.actor.{ Actor, ActorRef, PoisonPill, Props, actorRef2Scala }
import play.api.Logger
import play.api.libs.json.{ JsObject, Json, JsValue }
import play.api.libs.json.Json.toJsFieldJsValueWrapper

/**
 * Handles a WebSocket connection.
 */
class WebSocketActor(out: ActorRef) extends Actor {
  private val log = Logger(getClass)
  private var localMsgId = 1

  log.debug(s"WS actor created")

  def receive = {
    case JsObject(fields) if fields.isEmpty =>
      log.debug("empty json received, sending 'allowed'")
      out ! Json.obj("allowed" -> true, "salt" -> 1234)
    case json: JsValue =>
      val msgId = (json \ "msg").asOpt[Int]
      log.debug("json message received: " + json)
      msgId foreach { remoteMsgId =>
        out ! Json.obj("msg" -> localMsgId, "ack" -> remoteMsgId)
        localMsgId += 1
      }
  }

  override def postStop() = {}

  def close() = self ! PoisonPill
}

/**
 * Factory for WebSocket actors.
 */
object WebSocketActor {
  def props(out: ActorRef) = Props(new WebSocketActor(out))
}