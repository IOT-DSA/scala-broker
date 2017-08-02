package models.akka

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import models.Settings
import models.akka._
import akka.cluster.Cluster

/**
 * Represents a DSLink endpoint, which may or may not be connected to an Endpoint actor.
 * The Endpoint actor is supplied by the facade and can represent a WebSocket or TCP connection,
 * HTTP response stream, a test actor etc.
 *
 * The facade initiates a session by sending `ConnectEndpoint` message to the actor. The session
 * ends either when `DisconnectEndpoint` message is sent to an actor, or the endpoint actor terminates.
 */
class DefaultDSLinkActor(dslinkMgr: DSLinkManager) extends AbstractDSLinkActor
    with RequesterBehavior with PooledResponderBehavior {
  import models.Settings.Paths._

  /**
   * Performs post-stop actions on the requester.
   */
  override def postStop() = {
    stopRequester
    super.postStop
  }

  /**
   * Handles messages in CONNECTED state.
   */
  override def connected = super.connected orElse requesterBehavior orElse responderBehavior

  /**
   * Sends a message to an actor using its DSA link path.
   */
  def dsaSend(to: String, msg: Any) = {
    if (to == Downstream)
      context.actorSelection("/user/frontend") ! msg
    else if (to startsWith Downstream) {
      val linkName = to drop Downstream.size + 1
      dslinkMgr.tell(linkName, msg)
    } else if (isClusterMode)
      RootNodeActor.childProxy(to)(context.system) ! msg
    else
      context.actorSelection("/user/" + Settings.Nodes.Root + to) ! msg
  }
}

/**
 * Factory for [[DefaultDSLinkActor]] instances.
 */
object DefaultDSLinkActor {
  /**
   * Creates a new [[DefaultDSLinkActor]] instance.
   */
  def props(dslinkMgr: DSLinkManager) = Props(new DefaultDSLinkActor(dslinkMgr))
}