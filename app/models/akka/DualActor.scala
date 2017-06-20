package models.akka

import akka.actor.Props
import models.Settings

/**
 * Endpoint DSLink in DUAL mode.
 */
class DualActor(settings: Settings) extends DSLinkActor(settings)
    with RequesterBehavior with ResponderBehavior {

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
  def props(settings: Settings) = Props(new DualActor(settings))
}