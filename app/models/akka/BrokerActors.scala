package models.akka

import akka.actor.{ActorSystem}
import akka.cluster.Cluster
import javax.inject.{Inject, Singleton}

import models.akka.cluster.{ClusterContext, ClusteredDSLinkFolderActor}
import models.akka.local.LocalDSLinkFolderActor
import models.bench.BenchmarkActor

/**
 * A wrapper for essential actors to be started when the application starts.
 */
@Singleton
class BrokerActors @Inject() (actorSystem: ActorSystem,
                              clusterContext: ClusterContext) {
  import models.Settings._
  import models.rpc.DSAValue._

  private val downExtra: Seq[(String, DSAVal)] = List("downstream" -> true)
  private val upExtra: Seq[(String, DSAVal)] = List("upstream" -> true)

  val (root, downstream, upstream, benchmark) = if (actorSystem.hasExtension(Cluster))
    createClusteredActors
  else
    createLocalActors

  /**
   * Create actors for clusterless deployment.
   */
  private def createLocalActors = {
    val root = actorSystem.actorOf(RootNodeActor.props(clusterContext.distributedRegistry), Nodes.Root)

    val downstream = actorSystem.actorOf(LocalDSLinkFolderActor.props(
      Paths.Downstream, clusterContext.manager.dnlinkProps, downExtra: _*), Nodes.Downstream)

    val upstream = actorSystem.actorOf(LocalDSLinkFolderActor.props(
      Paths.Upstream, clusterContext.manager.uplinkProps, upExtra: _*), Nodes.Upstream)

    val bench = actorSystem.actorOf(BenchmarkActor.props(), "benchmark")

    (root, downstream, upstream, bench)
  }

  /**
   * Create actors for clustered deployment.
   */
  private def createClusteredActors = {
    val root = RootNodeActor.singletonStart(actorSystem, clusterContext.distributedRegistry)

    val downstream = actorSystem.actorOf(ClusteredDSLinkFolderActor.props(
      Paths.Downstream, clusterContext.manager.getDownlinkRoutee, downExtra: _*), Nodes.Downstream)

    val upstream = actorSystem.actorOf(ClusteredDSLinkFolderActor.props(
      Paths.Upstream, clusterContext.manager.getUplinkRoutee, upExtra: _*), Nodes.Upstream)

    val bench = actorSystem.actorOf(BenchmarkActor.props(), "benchmark")

    (root, downstream, upstream, bench)
  }
}