package models.actors

import akka.actor.{ ActorRef, Props }
import models.MessageRouter

/**
 * WebSocket actor connected to DSLink in REQUESTER mode.
 */
class RequesterActor(out: ActorRef, config: WebSocketActorConfig, val router: MessageRouter)
    extends AbstractWebSocketActor(out, config) with RequesterBehavior {

  override def receive = super.receive orElse requesterBehavior
  
  override def postStop() = {
    stopRequester
    super.postStop
  }
}

/**
 * Factory for [[RequesterActor]] instances.
 */
object RequesterActor {

  /**
   * Creates a new Props instance for RequesterActor.
   */
  def props(out: ActorRef, config: WebSocketActorConfig, router: MessageRouter) =
    Props(new RequesterActor(out, config, router))
}