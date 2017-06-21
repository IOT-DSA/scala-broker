package models.akka

import akka.actor.Props
import models.Settings

/**
 * Endpoint DSLink in REQUESTER mode.
 */
class RequesterActor(settings: Settings) extends DSLinkActor()(settings) with RequesterBehavior {

  override def receive = super.receive orElse requesterBehavior

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
  def props(settings: Settings) = Props(new RequesterActor(settings))
}