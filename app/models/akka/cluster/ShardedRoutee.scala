package models.akka.cluster

import akka.actor.ActorRef
import akka.routing.Routee
import models.akka.EntityEnvelope

/**
 * Sends messages using ShardRegion containing entities.
 */
final case class ShardedRoutee(region: ActorRef, entityId: String) extends Routee {

  /**
   * Sends a message to the entity via the shard region `tell`.
   */
  override def send(message: Any, sender: ActorRef): Unit = region.tell(wrap(message), sender)

  /**
   * Wraps the message into the entity envelope, which is used by the shard coordinator to route
   * it to the entity actor.
   */
  def wrap(msg: Any) = EntityEnvelope(entityId, msg)
}
