package models.akka

import scala.concurrent.Future
import scala.reflect.ClassTag

import akka.actor.{ Actor, ActorRef }
import models.akka.Messages.LinkInfo

/**
 * Manages interaction with DSLinks
 */
trait DSLinkManager {

  /**
   * Sends a fire-and-forget message to the DSLink.
   */
  def tell(linkName: String, msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit

  /**
   * Sends a message and returns a future response.
   */
  def ask[T: ClassTag](linkName: String, msg: Any)(implicit sender: ActorRef = Actor.noSender): Future[T]

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