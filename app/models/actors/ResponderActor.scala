package models.actors

import akka.actor.{ ActorRef, Props }
import play.api.cache.CacheApi

/**
 * WebSocket actor connected to DSLink in RESPONDER mode.
 */
class ResponderActor(out: ActorRef, connInfo: ConnectionInfo, cache: CacheApi)
    extends AbstractWebSocketActor(out, connInfo, cache) with ResponderBehavior {

  override def receive = super.receive orElse responderBehavior
}

/**
 * Factory for [[ResponderActor]] instances.
 */
object ResponderActor {

  /**
   * Creates a new Props instance for ResponderActor.
   */
  def props(out: ActorRef, connInfo: ConnectionInfo, cache: CacheApi) =
    Props(new ResponderActor(out, connInfo, cache))
}