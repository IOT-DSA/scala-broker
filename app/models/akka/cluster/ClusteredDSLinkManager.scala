package models.akka.cluster

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import akka.util.Timeout
import models.akka.{ DSLinkFactory, DSLinkManager, EntityEnvelope }
import models.metrics.EventDaos

/**
 * Uses Akka Cluster Sharding to communicate with DSLinks.
 */
class ClusteredDSLinkManager(frontendMode: Boolean, eventDaos: EventDaos)(implicit val system: ActorSystem) extends DSLinkManager {
  import models.Settings._

  implicit val timeout = Timeout(QueryTimeout)

  private val cluster = Cluster(system)

  log.info("Clustered DSLink Manager created")

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
    if (frontendMode)
      sharding.startProxy(Nodes.Downstream, Some("backend"), extractEntityId, extractShardId)
    else
      sharding.start(
        Nodes.Downstream,
        DSLinkFactory.props(this, eventDaos),
        ClusterShardingSettings(system),
        extractEntityId,
        extractShardId)
  }
}