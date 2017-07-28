package models.akka.cluster

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import models.Settings
import models.akka._

/**
 * Represents a DSLink endpoint, which may or may not be connected to an Endpoint actor.
 * The Endpoint actor is supplied by the facade and can represent a WebSocket or TCP connection,
 * HTTP response stream, a test actor etc.
 *
 * The facade initiates a session by sending `ConnectEndpoint` message to the actor. The session
 * ends either when `DisconnectEndpoint` message is sent to an actor, or the endpoint actor terminates.
 */
class DSLinkActor extends AbstractDSLinkActor with RequesterBehavior with PooledResponderBehavior {
  import models.Settings.Paths._

  /**
   * Registers itself with the Backend actor.
   */
  override def preStart() = {
    super.preStart
    context.actorSelection("/user/backend") ! BackendActor.RegisterDSLink
  }

  /**
   * Unregisters itself from the Backend actor.
   */
  override def postStop() = {
    stopRequester
    context.actorSelection("/user/backend") ! BackendActor.UnregisterDSLink
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
      context.actorSelection("/user/backend") ! msg
    else if (to startsWith Downstream) {
      val linkName = to drop Downstream.size + 1
      implicit val system = context.system
      new ShardedActorProxy(DSLinkActor.region, linkName) ! msg
    } else {
      context.actorSelection("/user/" + Settings.Nodes.Root + to) ! msg
    }
  }
}

/**
 * Factory for [[DSLinkActor]] instances and defined messages.
 */
object DSLinkActor {
  import models.Settings._
  import models.Settings.Nodes._

  /**
   * Creates a new instance of [[DSLinkActor]].
   */
  def props = Props(new DSLinkActor)

  /* CLUSTER */

  /**
   * Starts a shard region for Downstream dslink actors.
   */
  def regionStart(implicit system: ActorSystem): ActorRef = ClusterSharding(system).start(
    Downstream,
    props,
    ClusterShardingSettings(system),
    extractEntityId,
    extractShardId)

  /**
   * Starts a shard region proxy (which does not store actors) for Downstream dslink actors.
   */
  def proxyStart(implicit system: ActorSystem): ActorRef = ClusterSharding(system).startProxy(
    Downstream,
    Some("backend"),
    extractEntityId,
    extractShardId)

  /**
   * Returns the current shard region for Downstream dslink actors.
   */
  def region(implicit system: ActorSystem): ActorRef = ClusterSharding(system).shardRegion(Downstream)

  /**
   * Extracts DSLink name and payload from the message.
   */
  private val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(linkName, payload) => (linkName, payload)
  }

  /**
   * Extracts Shard Id from the message.
   */
  private val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(linkName, _) => (math.abs(linkName.hashCode) % DownstreamShardCount).toString
  }
}