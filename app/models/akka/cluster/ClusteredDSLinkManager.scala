package models.akka.cluster

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.Cluster
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.routing.Routee
import akka.util.Timeout
import models.akka.{DSLinkManager, RichRoutee, RootNodeActor, StandardActions}
import akka.cluster.ddata.DistributedData
import models.api.{DSANode, DSANodeDescription, DistributedNodesRegistry}
import models.api.DistributedNodesRegistry.{AddNode, RouteMessage}
import akka.pattern.ask
import models.Settings

import scala.concurrent.Future

/**
  * Uses Akka Cluster Sharding to communicate with DSLinks.
  */
class ClusteredDSLinkManager(proxyMode: Boolean)(implicit val system: ActorSystem) extends DSLinkManager {

  import models.Settings._
  import ClusteredDSLinkManager._

  implicit val ctx = system.dispatcher

  implicit val timeout = Timeout(QueryTimeout)

  private val cluster = Cluster(system)

  private val replicator = DistributedData(system).replicator
  private val distrubutedNodeRegistry: ActorRef = system
    .actorOf(DistributedNodesRegistry.props(replicator, cluster, system), DistributedNodesRegistry.ACTOR_NAME)


  (distrubutedNodeRegistry ? AddNode(DSANodeDescription.init(Paths.Data, Some("broker/dataRoot")))).mapTo[DSANode] foreach {
    _.displayName = "data"
  }

  (distrubutedNodeRegistry ? AddNode(DSANodeDescription.init(Paths.Sys, Some("broker/sysRoot")))).mapTo[DSANode] foreach {
    _.displayName = "sys"
  }

  (distrubutedNodeRegistry ? AddNode(DSANodeDescription.init(Paths.Tokens, Some("broker/tokensRoot"))))
    .mapTo[DSANode] foreach {
    node =>
      node.displayName = "tokens"
      StandardActions.bindTokenGroupNodeActions(node)
  }

  (distrubutedNodeRegistry ? AddNode(DSANodeDescription(Paths.Tokens + "/" + RootNodeActor.DEFAULT_TOKEN,
    Map("$is" -> "broker/token")))).mapTo[DSANode] foreach {
    node =>
      node.displayName = RootNodeActor.DEFAULT_TOKEN
      StandardActions.initTokenNode(node, RootNodeActor.DEFAULT_TOKEN, "config")
  }

  (distrubutedNodeRegistry ? AddNode(DSANodeDescription.init(Paths.Roles, Some("broker/rolesRoot"))))
    .mapTo[DSANode] foreach {
    node =>
      node.displayName = "roles"
      StandardActions.bindRolesNodeActions(node)
  }

  (distrubutedNodeRegistry ? AddNode(DSANodeDescription(Paths.Roles + "/" + RootNodeActor.DEFAULT_ROLE
    , RootNodeActor.DEFAULT_ROLE_CONFIG)))
    .mapTo[DSANode] foreach {
    node =>
      node.displayName = "default"
      node.value = "none"
      StandardActions.bindRoleNodeActions(node)
  }

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
    case Paths.Downstream => system.actorSelection("/user" + Paths.Downstream) ! message
    case path if path.startsWith(Paths.Downstream) => getDownlinkRoutee(path.drop(Paths.Downstream.size + 1)) ! message
    case Paths.Upstream => system.actorSelection("/user" + Paths.Upstream) ! message
    case path if path.startsWith(Paths.Upstream) => getUplinkRoutee(path.drop(Paths.Upstream.size + 1)) ! message
    case Paths.Data => routeToDistributed(path, message)
    case Paths.Sys => routeToDistributed(path, message)
    case path if path.startsWith(Paths.Data) => routeToDistributed(path, message)
    case path if path.startsWith(Paths.Tokens) => routeToDistributed(path, message)
    case path if path.startsWith(Paths.Roles) => routeToDistributed(path, message)
    //    case path if path.startsWith(Paths.Sys)         => system.actorSelection("/user" + Paths.Sys) ! message
    case path => RootNodeActor.childProxy(path)(system) ! message
  }

  /**
    * Ask message from its DSA destination using actor selection
    *
    * @param path
    * @param message
    * @param sender
    * @return
    */
  def dsaAsk(path: String, message: Any)(implicit sender: ActorRef = ActorRef.noSender): Future[Any] = path match {
    case Paths.Downstream =>
      system.actorSelection("/user" + Paths.Downstream) ? message
    case path if path.startsWith(Paths.Downstream) =>
      getDownlinkRoutee(path.drop(Paths.Downstream.size + 1)) ? message
    case Paths.Upstream => system.actorSelection("/user" + Paths.Upstream) ? message
    case path if path.startsWith(Paths.Upstream) => getUplinkRoutee(path.drop(Paths.Upstream.size + 1)) ? message
    case Paths.Data => ???
    case path if path.startsWith(Paths.Data) => ???
    case path if path.startsWith(Paths.Tokens) => distrubutedNodeRegistry ? message
    case path if path.startsWith(Paths.Roles) => distrubutedNodeRegistry ? message
    case path => RootNodeActor.childProxy(path)(system) ? message
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
    if (proxyMode) {
      sharding.startProxy(typeName, Some("backend"), extractEntityId, extractShardId)
    }
    else {
      val settings = ClusterShardingSettings(system)
      val threshold = settings.tuningParameters.leastShardAllocationRebalanceThreshold
      val maxSimultaneousRebalance = settings.tuningParameters.leastShardAllocationMaxSimultaneousRebalance

      sharding.start(
        typeName,
        linkProps,
        settings,
        ClusteredDSLinkManager.extractEntityId,
        ClusteredDSLinkManager.extractShardId,
         new CustomAllocationStrategy(threshold, maxSimultaneousRebalance),
          PoisonPill
      )
    }
  }

  private def routeToDistributed(path: String, message: Any)(implicit sender: ActorRef = ActorRef.noSender): Unit =
    distrubutedNodeRegistry ! RouteMessage(path, message, sender)

  private def ask4Distributed(path: String, message: Any)(implicit sender: ActorRef = ActorRef.noSender): Future[Any] =
    distrubutedNodeRegistry ? RouteMessage(path, message, sender)

  override def updateRoutee(routee: Routee): Routee = routee match {
    case ShardedRoutee(region, entityId) => region.path.name match {
      case Nodes.Downstream => getDownlinkRoutee(entityId)
      case Nodes.Upstream => getUplinkRoutee(entityId)
      case _ => routee
    }
    case _ => routee
  }

}

object ClusteredDSLinkManager {

  /**
    * Extracts DSLink name and payload from the message.
    */
  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(linkName, payload) => (linkName, payload)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(linkName, _) => (math.abs(linkName.hashCode) % Settings.DownstreamShardCount).toString
  }

}