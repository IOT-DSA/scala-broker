package models.akka.local

import akka.actor.{ ActorRef, ActorSystem }
import akka.routing.{ ActorSelectionRoutee, Routee }
import akka.util.Timeout
import models.akka.DSLinkManager
import models.util.DsaToAkkaCoder._

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
    ActorSelectionRoutee(system.actorSelection("/user/" + Nodes.Downstream + "/" + name.forAkka))

  /**
   * Returns a [[ActorSelectionRoutee]] instance for the specified uplink.
   */
  def getUplinkRoutee(name: String): Routee =
    ActorSelectionRoutee(system.actorSelection("/user/" + Nodes.Upstream + "/" + name.forAkka))

  /**
   * Sends a message to its DSA destination using actor selection.
   */
  def dsaSend(dsaPath: String, message: Any)(implicit sender: ActorRef = ActorRef.noSender): Unit = dsaPath match {
    case dsaPath if dsaPath.startsWith(Paths.Downstream) => system.actorSelection("/user" + dsaPath.forAkka) ! message
    case dsaPath if dsaPath.startsWith(Paths.Upstream)   => system.actorSelection("/user" + dsaPath.forAkka) ! message
    case dsaPath                                      => system.actorSelection("/user/" + Nodes.Root + dsaPath.forAkka) ! message
  }
}