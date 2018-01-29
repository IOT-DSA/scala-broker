package models.akka.cluster

import scala.concurrent.Future
import scala.reflect.ClassTag

import akka.actor.{ Actor, ActorLogging, RootActorPath }
import akka.pattern.ask
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.util.Timeout
import models.Settings

/**
 * An envelope for message routing, that provides the entityId for the shard coordinator.
 */
final case class EntityEnvelope(entityId: String, msg: Any)

/**
 * A wrapper for messages passed between peer actors in the cluster.
 */
final case class PeerMessage(payload: Any)

/**
 * Trait inherited by actors deployed on cluster nodes.
 */
trait ClusteredActor extends Actor with ActorLogging {

  implicit val timeout = Timeout(Settings.QueryTimeout)

  import context.dispatcher

  /**
   * Akka cluster.
   */
  val cluster = Cluster(context.system)

  /**
   * Distributed data replicator.
   */
  val replicator = DistributedData(context.system).replicator

  /**
   * Returns a set of actor selection instances for each downstream node in the cluster.
   */
  protected def peers(includeSelf: Boolean = true) = {
    val members = if (includeSelf) cluster.state.members else cluster.state.members - cluster.selfMember
    members map { member =>
      context.actorSelection(RootActorPath(member.address) / self.path.elements)
    }
  }

  /**
   * Sends a message to all downstream nodes in the cluster and collects the responses into a map.
   */
  protected def askPeers[T: ClassTag](request: Any, includeSelf: Boolean = true) = {
    val results = peers(includeSelf) map (p => p.ask(request).mapTo[T].map(x => p.anchorPath.address -> x))
    Future.sequence(results) map (_.toMap)
  }

  /**
   * Sends a message to all peers.
   */
  protected def tellPeers(msg: Any, includeSelf: Boolean = true) = peers(includeSelf) foreach (_ ! msg)

  /**
   * Forwards a message to all peers.
   */
  protected def forwardToPeers(msg: Any, includeSelf: Boolean = true) = peers(includeSelf) foreach (_.forward(msg))
}