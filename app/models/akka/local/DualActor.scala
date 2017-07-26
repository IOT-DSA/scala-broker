package models.akka.local

import akka.actor.Props
import models.akka.ConnectionInfo

/**
 * Endpoint DSLink in DUAL mode.
 */
class DualActor(connInfo: ConnectionInfo) extends DSLinkActor(connInfo) with RequesterBehavior with PooledResponderBehavior {

  override def connected = super.connected orElse requesterBehavior orElse responderBehavior

  override def postStop() = {
    stopRequester
    super.postStop
  }
}

/**
 * Factory for [[DualActor]] instances.
 */
object DualActor {
  /**
   * Creates a new Props instance for [[DualActor]].
   */
  def props(connInfo: ConnectionInfo) = Props(new DualActor(connInfo))
}