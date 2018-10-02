package models.akka.cluster

import akka.actor.ActorRef
import akka.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId

import scala.collection.immutable
import scala.concurrent.Future

class CustomAllocationStrategy(rebalanceThreshold: Int, maxSimultaneousRebalance: Int)
  extends LeastShardAllocationStrategy(rebalanceThreshold, maxSimultaneousRebalance) {
  override def allocateShard(requester: ActorRef,
                             shardId: ShardId,
                             currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Future[ActorRef] = {

    val localSize = currentShardAllocations(requester).size
    val treshold = 20 //TODO move to config

    val (regionWithLeastShards, value) = currentShardAllocations.minBy { case (_, v) ⇒ v.size }

    val toShardOn = if(value.size + 20 < localSize) regionWithLeastShards else requester

    Future.successful(toShardOn)

  }

}
