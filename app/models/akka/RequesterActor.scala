package models.akka

import akka.actor.Props
import models.Settings

/**
 * Endpoint DSLink in REQUESTER mode.
 */
class RequesterActor(connInfo: ConnectionInfo) extends DSLinkActor(connInfo) with RequesterBehavior {

  override def connected = super.connected orElse requesterBehavior

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
   * Creates a new Props instance for [[RequesterActor]].
   */
  def props(connInfo: ConnectionInfo) = Props(new RequesterActor(connInfo))
}