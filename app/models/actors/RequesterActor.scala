package models.actors

import akka.actor.{ ActorRef, Props }
import play.api.cache.CacheApi

/**
 * WebSocket actor connected to DSLink in REQUESTER mode.
 */
class RequesterActor(out: ActorRef, connInfo: ConnectionInfo, cache: CacheApi)
    extends AbstractWebSocketActor(out, connInfo, cache) with RequesterBehavior {

  override def receive = super.receive orElse requesterBehavior
}

/**
 * Factory for [[RequesterActor]] instances.
 */
object RequesterActor {

  /**
   * Creates a new Props instance for RequesterActor.
   */
  def props(out: ActorRef, connInfo: ConnectionInfo, cache: CacheApi) =
    Props(new RequesterActor(out, connInfo, cache))
}