package models.akka.cluster

import scala.concurrent.Future

import akka.actor.{ Actor, ActorRef }
import akka.routing.Routee
import akka.util.Timeout

/**
 * Sends messages using ShardRegion containing entities.
 */
final case class ShardedRoutee(region: ActorRef, entityId: String) extends Routee {

  /**
   * Sends a message to the entity via the shard region `tell`.
   */
  override def send(message: Any, sender: ActorRef): Unit = region.tell(wrap(message), sender)

  /**
   * Sends a message and returns a future response.
   */
  def ask(msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Any] =
    akka.pattern.ask(region, wrap(msg), sender)(timeout).mapTo[Any]

  /**
   * Wraps the message into the entity envelope, which is used by the shard coordinator to route
   * it to the entity actor.
   */
  private def wrap(msg: Any) = EntityEnvelope(entityId, msg)
}