package models.akka.cluster

import akka.actor.{ ActorRef, ActorSystem }
import akka.cluster.Cluster
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import akka.routing.Routee
import akka.util.Timeout
import models.akka.{ DSLinkManager, RichRoutee, RootNodeActor }
import akka.actor.Props

/**
 * Uses Akka Cluster Sharding to communicate with DSLinks.
 */
class ClusteredDSLinkManager(proxyMode: Boolean)(implicit val system: ActorSystem) extends DSLinkManager {
  import models.Settings._

  implicit val timeout = Timeout(QueryTimeout)

  private val cluster = Cluster(system)

  log.info("Clustered DSLink Manager created")

  /**
   * Returns a [[ShardedRoutee]] instance for the specified downlink.
   */
  def getDownlinkRoutee(name: String): Routee = ShardedRoutee(dnlinkRegion, name)

  /**
   * Returns a [[ShardedRoutee]] instance for the specified uplink.
   */
  def getUplinkRoutee(name: String): Routee = ShardedRoutee(uplinkRegion, name)

  /**
   * Sends a message to its DSA destination using Akka Sharding for dslinks and Singleton for root node.
   */
  def dsaSend(path: String, message: Any)(implicit sender: ActorRef = ActorRef.noSender): Unit = path match {
    case Paths.Downstream                          => system.actorSelection("/user" + Paths.Downstream) ! message
    case path if path.startsWith(Paths.Downstream) => getDownlinkRoutee(path.drop(Paths.Downstream.size + 1)) ! message
    case Paths.Upstream                            => system.actorSelection("/user" + Paths.Upstream) ! message
    case path if path.startsWith(Paths.Upstream)   => getUplinkRoutee(path.drop(Paths.Upstream.size + 1)) ! message
    case path                                      => RootNodeActor.childProxy(path)(system) ! message
  }

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
   * Shard region for downstream links.
   */
  val dnlinkRegion = createRegion(Nodes.Downstream, dnlinkProps)
  
  /**
   * Shard region for upstream links.
   */
  val uplinkRegion = createRegion(Nodes.Upstream, uplinkProps)

  /**
   * Creates a sharding region or connects to the sharding system in proxy mode.
   */
  private def createRegion(typeName: String, linkProps: Props) = {
    val sharding = ClusterSharding(system)
    if (proxyMode)
      sharding.startProxy(typeName, Some("backend"), extractEntityId, extractShardId)
    else
      sharding.start(typeName, linkProps, ClusterShardingSettings(system), extractEntityId, extractShardId)
  }
}