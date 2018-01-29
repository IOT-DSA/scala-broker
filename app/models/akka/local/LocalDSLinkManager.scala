package models.akka.local

import akka.actor.{ ActorRef, ActorSystem }
import akka.routing.{ ActorSelectionRoutee, Routee }
import akka.util.Timeout
import models.akka.DSLinkManager
import models.metrics.EventDaos

/**
 * Uses Akka Actor Selection to communicate with DSLinks.
 */
class LocalDSLinkManager(val eventDaos: EventDaos)(implicit val system: ActorSystem) extends DSLinkManager {
  import models.Settings._

  implicit val timeout = Timeout(QueryTimeout)

  log.info("Local DSLink Manager created")

  /**
   * Returns a [[ActorSelectionRoutee]] instance for the specified dslink.
   */
  def getDSLinkRoutee(name: String): Routee =
    ActorSelectionRoutee(system.actorSelection("/user/" + Nodes.Downstream + "/" + name))

  /**
   * Sends a message to its DSA destination using actor selection.
   */
  def dsaSend(path: String, message: Any)(implicit sender: ActorRef = ActorRef.noSender): Unit = path match {
    case path if path.startsWith(Paths.Downstream) => system.actorSelection("/user" + path) ! message
    case path                                      => system.actorSelection("/user/" + Nodes.Root + path) ! message
  }
}