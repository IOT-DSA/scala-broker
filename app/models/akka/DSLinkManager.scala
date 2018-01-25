package models.akka

import akka.actor.ActorSystem
import play.api.Logger

/**
 * Manages interaction with DSLinks
 */
trait DSLinkManager {
  def system: ActorSystem

  protected val log = Logger(getClass)
}