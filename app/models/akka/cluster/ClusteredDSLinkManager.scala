package models.akka.cluster

import akka.actor.{ ActorSystem, Actor, ActorRef }
import models.akka.{ DSLinkManager, EntityEnvelope, ShardedActorProxy, ConnectionInfo, DefaultDSLinkActor }
import akka.util.Timeout
import akka.cluster.sharding.{ ShardRegion, ClusterSharding, ClusterShardingSettings }
import scala.reflect.ClassTag

/**
 * Uses Akka Cluster Sharding to communicate with DSLinks.
 */
class ClusteredDSLinkManager(implicit system: ActorSystem) extends DSLinkManager {
  import models.Settings._
  import models.akka.Messages._

  implicit val timeout = Timeout(QueryTimeout)

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

  /**
   * Create shard region.
   */
  private val region = ClusterSharding(system).start(
    Nodes.Downstream,
    DefaultDSLinkActor.props(this),
    ClusterShardingSettings(system),
    extractEntityId,
    extractShardId)

  /**
   * Sends a message to the DSLink using the shard region.
   */
  def tell(linkName: String, msg: Any)(implicit sender: ActorRef = Actor.noSender) =
    region.tell(wrap(linkName, msg), sender)

  /**
   * Sends a request-response message to the DSLink using the shard region.
   */
  def ask[T: ClassTag](linkName: String, msg: Any)(implicit sender: ActorRef = Actor.noSender) =
    akka.pattern.ask(region, wrap(linkName, msg), sender).mapTo[T]

  def connectEndpoint(linkName: String, ep: ActorRef, ci: ConnectionInfo) =
    tell(linkName, ConnectEndpoint(ep, ci))

  def disconnectEndpoint(linkName: String, killEndpoint: Boolean = true) =
    tell(linkName, DisconnectEndpoint(killEndpoint))

  def getDSLinkInfo(linkName: String) = ask[LinkInfo](linkName, GetLinkInfo)

  /**
   * Creates an instance of [[ShardedActorProxy]].
   */
  def getCommProxy(linkName: String) = new ShardedActorProxy(region, linkName)
  
  /**
   * Wraps the message into the entity envelope, which is used by the shard coordinator to route
   * it to the entity actor.
   */
  private def wrap(linkName: String, msg: Any) = EntityEnvelope(linkName, msg)
}