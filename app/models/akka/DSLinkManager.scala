package models.akka

import scala.concurrent.Future
import scala.reflect.ClassTag

import akka.actor.{ Actor, ActorRef, ActorSystem }
import models.akka.Messages.LinkInfo

/**
 * Manages interaction with DSLinks
 */
trait DSLinkManager {
  import models.Settings.Paths._

  def system: ActorSystem

  /**
   * Sends a fire-and-forget message to the DSLink.
   */
  def tellDSLink(linkName: String, msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit

  /**
   * Sends a message and returns a future response.
   */
  def askDSLink[T: ClassTag](linkName: String, msg: Any)(implicit sender: ActorRef = Actor.noSender): Future[T]

  /**
   * Sends a message to a non-DSLink node, like `/`, `/data/...` etc.
   * The `path` should start with `/` character.
   */
  def tellNode(path: String, message: Any)(implicit sender: ActorRef = Actor.noSender): Unit

  /**
   * Sends a message to an arbitrary DSA path.
   */
  def dsaSend(to: String, msg: Any)(implicit sender: ActorRef = Actor.noSender) =
    if (to.startsWith(Downstream) && to != Downstream)
      tellDSLink(to drop Downstream.size + 1, msg)
    else
      tellNode(to, msg)

  /**
   * Connects DSLink to an endpoint.
   */
  def connectEndpoint(linkName: String, ep: ActorRef, ci: ConnectionInfo): Unit

  /**
   * Disconnects DSLink from the associated endpoint.
   */
  def disconnectEndpoint(linkName: String, killEndpoint: Boolean = true): Unit

  /**
   * Returns a future with the information on the particular DSLink.
   */
  def getDSLinkInfo(linkName: String): Future[LinkInfo]

  /**
   * Returns a communication proxy for this DSLink. This call may block as it ensures the link exists.
   */
  def getCommProxy(linkName: String): CommProxy
}