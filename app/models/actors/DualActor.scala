package models.actors

import akka.actor.{ ActorRef, Props }
import play.api.cache.CacheApi
import models.Settings

/**
 * WebSocket actor connected to DSLink in DUAL mode.
 */
class DualActor(out: ActorRef, settings: Settings, connInfo: ConnectionInfo, cache: CacheApi)
    extends AbstractWebSocketActor(out, settings, connInfo, cache)
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
  def props(out: ActorRef, settings: Settings, connInfo: ConnectionInfo, cache: CacheApi) =
    Props(new DualActor(out, settings, connInfo, cache))
}