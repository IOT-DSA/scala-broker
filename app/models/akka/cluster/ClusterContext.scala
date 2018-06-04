package models.akka.cluster

import javax.inject.Singleton

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import com.google.inject.Inject
import models.akka.DSLinkManager
import models.akka.local.LocalDSLinkManager
import models.api.DistributedNodesRegistry

@Singleton
class ClusterContext @ Inject() (val actorSystem: ActorSystem){

  val isCluster = actorSystem.hasExtension(Cluster)

  val manager: DSLinkManager = if (isCluster)
    new ClusteredDSLinkManager(false)(actorSystem)
  else
    new LocalDSLinkManager()(actorSystem)

  val distributedRegistry:Option[ActorRef] = if(isCluster){
    val cluster = Cluster(actorSystem)
    val replicator = DistributedData(actorSystem).replicator
    val props = DistributedNodesRegistry.props(replicator, cluster, actorSystem)
    val registry = actorSystem.actorOf(props, "DistributedStateRegistry")
    Some(registry)
  } else None

}
