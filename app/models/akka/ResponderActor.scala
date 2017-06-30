package models.akka

import akka.actor.Props
import models.Settings

/**
 * Endpoint DSLink in RESPONDER mode.
 */
class ResponderActor extends DSLinkActor with SimpleResponderBehavior {

  override def receive = super.receive orElse responderBehavior
}

/**
 * Factory for [[ResponderActor]] instances.
 */
object ResponderActor {
  /**
   * Creates a new Props instance for [[ResponderActor]].
   */
  def props = Props(new ResponderActor)
}