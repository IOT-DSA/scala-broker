package models.akka

import scala.concurrent.Future
import scala.reflect.ClassTag

import akka.actor.{ Actor, ActorRef, ActorSystem }
import akka.pattern.{ ask => query }
import akka.util.Timeout
import akka.cluster.pubsub.DistributedPubSubMediator.Send

/**
 * Provides communication to a particular entity (an actor).
 */
trait CommProxy {
  /**
   * Sends a fire-and-forget message.
   */
  def tell(msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit

  /**
   * An alias for `tell`.
   */
  def !(msg: Any)(implicit sender: ActorRef = Actor.noSender) = tell(msg)

  /**
   * Sends a message and returns a future response.
   */
  def ask[T: ClassTag](msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[T]

  /**
   * An alias for `ask`.
   */
  def ?[T: ClassTag](msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender) = ask[T](msg)
}

/**
 * Directly sends messages using ActorRef.
 */
class ActorRefProxy(ref: ActorRef) extends CommProxy {
  /**
   * Sends a message to the actor using `ActorRef.tell`.
   */
  def tell(msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit = ref.tell(msg, sender)

  /**
   * Sends a message using Akka `ask` helper and returns a future response.
   */
  def ask[T: ClassTag](msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[T] =
    query(ref, msg, sender)(timeout).mapTo[T]
}

/**
 * Sends messages using ActorSelection based on the path.
 */
class ActorPathProxy(path: String)(implicit system: ActorSystem) extends CommProxy {
  private val selection = system.actorSelection(path)

  /**
   * Sends a message to the actor using `ActorSelection.tell`.
   */
  def tell(msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit = selection.tell(msg, sender)

  /**
   * Sends a message using ActorSelection `ask` helper and returns a future response.
   */
  def ask[T: ClassTag](msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[T] =
    selection.ask(msg)(timeout, sender).mapTo[T]
}

/**
 * Sends messages using ShardRegion containing entities.
 */
class ShardedActorProxy(region: ActorRef, entityId: String)(implicit system: ActorSystem) extends CommProxy {

  /**
   * Sends a message to the entity via the shard region `tell`.
   */
  def tell(msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit = region.tell(wrap(msg), sender)

  /**
   * Sends a message using the shard region `ask` and returns a future response.
   */
  def ask[T: ClassTag](msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[T] =
    query(region, wrap(msg), sender)(timeout).mapTo[T]

  /**
   * Wraps the message into the entity envelope, which is used by the shard coordinator to route
   * it to the entity actor.
   */
  private def wrap(msg: Any) = EntityEnvelope(entityId, msg)
}

/**
 * Sends messages using distributed PubSub mediator.
 */
class PubSubProxy(mediator: ActorRef, path: String) extends CommProxy {

  /**
   * Sends a message to the entity via the mediator `tell`.
   */
  def tell(msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit = mediator.tell(wrap(msg), sender)

  /**
   * Sends a message using the mediator `ask` and returns a future response.
   */
  def ask[T: ClassTag](msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[T] =
    query(mediator, wrap(msg), sender)(timeout).mapTo[T]

  /**
   * Wraps the message into the PubSub `Send` envelope.
   */
  private def wrap(msg: Any) = Send(path = path, msg = msg, localAffinity = true)
}