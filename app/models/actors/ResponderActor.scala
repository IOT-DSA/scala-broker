package models.actors

import akka.actor.{ ActorRef, Props }
import models.MessageRouter

/**
 * WebSocket actor connected to DSLink in RESPONDER mode.
 */
class ResponderActor(out: ActorRef, config: WebSocketActorConfig, val router: MessageRouter)
    extends AbstractWebSocketActor(out, config) with ResponderBehavior {

  override def receive = super.receive orElse responderBehavior
}

/**
 * Factory for [[ResponderActor]] instances.
 */
object ResponderActor {

  /**
   * Creates a new Props instance for ResponderActor.
   */
  def props(out: ActorRef, config: WebSocketActorConfig, router: MessageRouter) =
    Props(new ResponderActor(out, config, router))
}