package models.akka

import akka.actor.Props
import models.Settings

/**
 * Endpoint DSLink in RESPONDER mode.
 */
class ResponderActor(settings: Settings) extends DSLinkActor(settings) with ResponderBehavior {

  override def receive = super.receive orElse responderBehavior
}

/**
 * Factory for [[ResponderActor]] instances.
 */
object ResponderActor {
  /**
   * Creates a new Props instance for [[ResponderActor]].
   */
  def props(settings: Settings) = Props(new ResponderActor(settings))
}