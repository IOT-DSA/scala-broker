package models.akka

import akka.actor.Props
import models.Settings

/**
 * Endpoint DSLink in REQUESTER mode.
 */
class RequesterActor extends DSLinkActor with RequesterBehavior {

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
  def props = Props(new RequesterActor)
}