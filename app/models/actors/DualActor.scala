package models.actors

import akka.actor.{ ActorRef, Props }
import play.api.cache.CacheApi

/**
 * WebSocket actor connected to DSLink in DUAL mode.
 */
class DualActor(out: ActorRef, connInfo: ConnectionInfo, cache: CacheApi)
    extends AbstractWebSocketActor(out, connInfo, cache) with RequesterBehavior with ResponderBehavior {

  override def receive = super.receive orElse requesterBehavior orElse responderBehavior
}

/**
 * Factory for [[DualActor]] instances.
 */
object DualActor {

  /**
   * Creates a new Props instance for DualActor.
   */
  def props(out: ActorRef, connInfo: ConnectionInfo, cache: CacheApi) =
    Props(new DualActor(out, connInfo, cache))
}