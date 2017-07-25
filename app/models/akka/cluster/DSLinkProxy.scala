package models.akka.cluster

import scala.concurrent.Future
import scala.reflect.ClassTag

import akka.actor.{ Actor, ActorRef, ActorSystem }
import akka.pattern.{ ask => query, pipe }
import akka.util.Timeout

/**
 * Delivers messages to a particular DSLinkActor. It masks clustered vs non-clustered implementation
 * details in its subclasses.
 */
abstract class DSLinkProxy {
  /**
   * Sends a fire-and-forget message to the DSLinkActor.
   */
  def tell(msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit

  /**
   * Sends a message to the DSLinkActor and returns a response.
   */
  def ask[T: ClassTag](msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[T]
}

/**
 * Directly sends messages using DSLink's ActorRef.
 */
class DirectDSLinkProxy(ref: ActorRef) extends DSLinkProxy {
  def tell(msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit = ref.tell(msg, sender)

  def ask[T: ClassTag](msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[T] =
    query(ref, msg, sender)(timeout).mapTo[T]
}

/**
 * Sends messages using ActorSelection based on DSLink's name.
 */
class PathDSLinkProxy(linkName: String, system: ActorSystem) extends DSLinkProxy {
  import models.Settings.Nodes._

  private val selection = system.actorSelection("/user/" + Root + "/" + Downstream + "/" + linkName)

  def tell(msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit = selection.tell(msg, sender)

  def ask[T: ClassTag](msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[T] =
    selection.ask(msg)(timeout, sender).mapTo[T]
}

/**
 * Sends messages using ShardRegion containin DSLink entities.
 */
class ShardedDSLinkProxy(linkName: String)(implicit system: ActorSystem) extends DSLinkProxy {
  import DSLinkActor._

  def tell(msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit = DSLinkActor.region.tell(wrap(msg), sender)

  def ask[T: ClassTag](msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[T] =
    query(region, wrap(msg), sender)(timeout).mapTo[T]

  private def wrap(msg: Any) = DSLinkEnvelope(linkName, msg)
}

/**
 * Sends messages using distributed PubSub mediator.
 */
class PubSubDSLinkProxy(linkName: String, mediator: ActorRef) extends DSLinkProxy {
  import akka.cluster.pubsub.DistributedPubSubMediator.Send
  import models.Settings.Nodes._

  private val linkPath = "/user/" + Root + "/" + Downstream + "/" + linkName

  def tell(msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit = mediator.tell(wrap(msg), sender)

  def ask[T: ClassTag](msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[T] =
    query(mediator, wrap(msg), sender)(timeout).mapTo[T]

  private def wrap(msg: Any) = Send(path = linkPath, msg = msg, localAffinity = true)
}