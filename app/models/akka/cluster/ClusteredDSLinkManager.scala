package models.akka.cluster

import akka.actor.{ ActorSystem, Actor, ActorRef }
import models.akka._
import akka.util.Timeout
import akka.cluster.sharding.{ ShardRegion, ClusterSharding, ClusterShardingSettings }
import scala.reflect.ClassTag
import akka.cluster.Cluster
import akka.cluster.MemberStatus
import akka.actor.RootActorPath

/**
 * Uses Akka Cluster Sharding to communicate with DSLinks.
 */
class ClusteredDSLinkManager(frontendMode: Boolean)(implicit val system: ActorSystem) extends DSLinkManager {
  import models.Settings._
  import models.akka.Messages._

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
  private val region = {
    val sharding = ClusterSharding(system)
    if (frontendMode)
      sharding.startProxy(Nodes.Downstream, Some("backend"), extractEntityId, extractShardId)
    else
      sharding.start(
        Nodes.Downstream,
        DSLinkFactory.props(this),
        ClusterShardingSettings(system),
        extractEntityId,
        extractShardId)
  }

  /**
   * Sends a message to the DSLink using the shard region.
   */
  def tellDSLink(linkName: String, msg: Any)(implicit sender: ActorRef = Actor.noSender) =
    region.tell(wrap(linkName, msg), sender)

  /**
   * Sends a request-response message to the DSLink using the shard region.
   */
  def askDSLink[T: ClassTag](linkName: String, msg: Any)(implicit sender: ActorRef = Actor.noSender) =
    akka.pattern.ask(region, wrap(linkName, msg), sender).mapTo[T]

  /**
   * Sends a message to a root node's child using singleton proxy.
   */
  def tellNode(path: String, message: Any)(implicit sender: ActorRef = Actor.noSender) =
    if (path == Paths.Downstream)
      cluster.state.members.filter(_.status == MemberStatus.Up) foreach { member =>
        val backend = system.actorSelection(RootActorPath(member.address) / "user" / "backend")
        backend ! message
      }
    else
      RootNodeActor.childProxy(path)(system) ! message

  def connectEndpoint(linkName: String, ep: ActorRef, ci: ConnectionInfo) =
    tellDSLink(linkName, ConnectEndpoint(ep, ci))

  def disconnectEndpoint(linkName: String, killEndpoint: Boolean = true) =
    tellDSLink(linkName, DisconnectEndpoint(killEndpoint))

  def getDSLinkInfo(linkName: String) = askDSLink[LinkInfo](linkName, GetLinkInfo)

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