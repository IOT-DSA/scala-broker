package models.actors

import akka.actor.{ ActorRef, Props }
import models.MessageRouter

/**
 * WebSocket actor connected to DSLink in DUAL mode.
 */
class DualActor(out: ActorRef, config: WebSocketActorConfig, val router: MessageRouter)
    extends AbstractWebSocketActor(out, config)
    with RequesterBehavior with ResponderBehavior {

  override def receive = super.receive orElse requesterBehavior orElse responderBehavior
}

/**
 * Factory for [[DualActor]] instances.
 */
object DualActor {

  /**
   * Creates a new Props instance for DualActor.
   */
  def props(out: ActorRef, config: WebSocketActorConfig, router: MessageRouter) =
    Props(new DualActor(out, config, router))
}