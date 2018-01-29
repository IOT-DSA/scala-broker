package models.akka.cluster

import akka.actor.{ ActorRef, ActorSystem }
import akka.cluster.Cluster
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import akka.routing.Routee
import akka.util.Timeout
import models.akka.{ DSLinkManager, RichRoutee, RootNodeActor }
import models.metrics.EventDaos

/**
 * Uses Akka Cluster Sharding to communicate with DSLinks.
 */
class ClusteredDSLinkManager(proxyMode: Boolean, val eventDaos: EventDaos)(implicit val system: ActorSystem) extends DSLinkManager {
  import models.Settings._

  implicit val timeout = Timeout(QueryTimeout)

  private val cluster = Cluster(system)

  log.info("Clustered DSLink Manager created")

  /**
   * Returns a [[ShardedRoutee]] instance for the specified dslink.
   */
  def getDSLinkRoutee(name: String): Routee = ShardedRoutee(region, name)

  /**
   * Sends a message to its DSA destination using Akka Sharding for dslinks and Singleton for root node.
   */
  def dsaSend(path: String, message: Any)(implicit sender: ActorRef = ActorRef.noSender): Unit = path match {
    case Paths.Downstream                          => system.actorSelection("/user" + Paths.Downstream) ! message
    case path if path.startsWith(Paths.Downstream) => getDSLinkRoutee(path.drop(Paths.Downstream.size + 1)) ! message
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
   * Create shard region.
   */
  val region = {
    val sharding = ClusterSharding(system)
    if (proxyMode)
      sharding.startProxy(Nodes.Downstream, Some("backend"), extractEntityId, extractShardId)
    else
      sharding.start(
        Nodes.Downstream,
        props,
        ClusterShardingSettings(system),
        extractEntityId,
        extractShardId)
  }
}