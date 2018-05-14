package models.akka.local

import akka.actor.{ ActorRef, ActorSystem }
import akka.routing.{ ActorSelectionRoutee, Routee }
import akka.util.Timeout
import models.akka.DSLinkManager

/**
 * Uses Akka Actor Selection to communicate with DSLinks.
 */
class LocalDSLinkManager()(implicit val system: ActorSystem) extends DSLinkManager {
  import models.Settings._

  implicit val timeout = Timeout(QueryTimeout)

  log.info("Local DSLink Manager created")

  /**
   * Returns a [[ActorSelectionRoutee]] instance for the specified downlink.
   */
  def getDownlinkRoutee(name: String): Routee =
    ActorSelectionRoutee(system.actorSelection("/user/" + Nodes.Downstream + "/" + name))

  /**
   * Returns a [[ActorSelectionRoutee]] instance for the specified uplink.
   */
  def getUplinkRoutee(name: String): Routee =
    ActorSelectionRoutee(system.actorSelection("/user/" + Nodes.Upstream + "/" + name))

  /**
   * Sends a message to its DSA destination using actor selection.
   */
  def dsaSend(path: String, message: Any)(implicit sender: ActorRef = ActorRef.noSender): Unit = path match {
    case path if path.startsWith(Paths.Downstream) => system.actorSelection("/user" + path) ! message
    case path if path.startsWith(Paths.Upstream)   => system.actorSelection("/user" + path) ! message
    case path                                      => system.actorSelection("/user/" + Nodes.Root + path) ! message
  }
}