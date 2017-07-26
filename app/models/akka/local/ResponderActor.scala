package models.akka.local

import akka.actor.Props
import models.akka.ConnectionInfo

/**
 * Endpoint DSLink in RESPONDER mode.
 */
class ResponderActor(connInfo: ConnectionInfo) extends DSLinkActor(connInfo) with PooledResponderBehavior {

  override def connected = super.connected orElse responderBehavior  
}

/**
 * Factory for [[ResponderActor]] instances.
 */
object ResponderActor {
  /**
   * Creates a new Props instance for [[ResponderActor]].
   */
  def props(connInfo: ConnectionInfo) = Props(new ResponderActor(connInfo))
}