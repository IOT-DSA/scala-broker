package models.akka.local

import akka.actor.ActorSystem
import akka.util.Timeout
import models.akka.DSLinkManager

/**
 * Uses Akka Actor Selection to communicate with DSLinks.
 */
class LocalDSLinkManager(implicit val system: ActorSystem) extends DSLinkManager {
  import models.Settings._

  implicit val timeout = Timeout(QueryTimeout)

  log.info("Local DSLink Manager created")
}