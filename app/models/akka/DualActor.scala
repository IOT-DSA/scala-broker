package models.akka

import akka.actor.Props
import models.Settings

/**
 * Endpoint DSLink in DUAL mode.
 */
class DualActor extends DSLinkActor with RequesterBehavior with SimpleResponderBehavior {

  override def receive = super.receive orElse requesterBehavior orElse responderBehavior

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
  def props = Props(new DualActor)
}