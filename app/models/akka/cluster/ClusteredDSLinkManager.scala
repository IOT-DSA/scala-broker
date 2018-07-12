package models.akka.cluster

import akka.actor.{ ActorRef, ActorSystem }
import akka.cluster.Cluster
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import akka.routing.Routee
import akka.util.Timeout
import models.akka.{ DSLinkManager, RichRoutee, RootNodeActor }
import akka.actor.Props
import akka.cluster.ddata.DistributedData
import models.api.{DSANode, DSANodeDescription, DistributedNodesRegistry}
import models.api.DistributedNodesRegistry.{AddNode, RouteMessage}
import akka.pattern.ask

/**
 * Uses Akka Cluster Sharding to communicate with DSLinks.
 */
class ClusteredDSLinkManager(proxyMode: Boolean)(implicit val system: ActorSystem) extends DSLinkManager {
  import models.Settings._

  implicit val ctx = system.dispatcher

  implicit val timeout = Timeout(QueryTimeout)

  private val cluster = Cluster(system)

  private val replicator = DistributedData(system).replicator
  private val distrubutedNodeRegistry:ActorRef = system
    .actorOf(DistributedNodesRegistry.props(replicator, cluster, system), "distributedNodesRegistry")


  (distrubutedNodeRegistry ? AddNode(DSANodeDescription.init("/data", Some("broker/dataRoot")))).mapTo[DSANode] foreach{
    node =>
      node.displayName = "data"
  }

//  (distrubutedNodeRegistry ? AddNode(DSANodeDescription.init("/sys/Tokens", Some("broker/tokens")))).mapTo[DSANode] foreach{
//    node =>
//      node.displayName = "/Tokens"
//  }


  log.info("Clustered DSLink Manager created")

  /**
   * Returns a [[ShardedRoutee]] instance for the specified downlink.
   */
  def getDownlinkRoutee(dsaName: String): Routee = ShardedRoutee(dnlinkRegion, dsaName)

  /**
   * Returns a [[ShardedRoutee]] instance for the specified uplink.
   */
  def getUplinkRoutee(dsaName: String): Routee = ShardedRoutee(uplinkRegion, dsaName)

  /**
   * Sends a message to its DSA destination using Akka Sharding for dslinks and Singleton for root node.
   */
  def dsaSend(path: String, message: Any)(implicit sender: ActorRef = ActorRef.noSender): Unit = path match {
    case Paths.Downstream                          => system.actorSelection("/user" + Paths.Downstream) ! message
    case path if path.startsWith(Paths.Downstream) => getDownlinkRoutee(path.drop(Paths.Downstream.size + 1)) ! message
    case Paths.Upstream                            => system.actorSelection("/user" + Paths.Upstream) ! message
    case path if path.startsWith(Paths.Upstream)   => getUplinkRoutee(path.drop(Paths.Upstream.size + 1)) ! message
    case Paths.Data                                => routeToDistributed(path, message)
    case path if path.startsWith(Paths.Data)       => routeToDistributed(path, message)
//    case path if path.startsWith(Paths.Sys)        => routeToDistributed(path, message)//
//    case path if path.startsWith(Paths.Sys)         => system.actorSelection("/user" + Paths.Sys) ! message
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

  private def routeToDistributed(path:String, message:Any)(implicit sender: ActorRef = ActorRef.noSender): Unit =
    distrubutedNodeRegistry ! RouteMessage(path, message, sender)

}