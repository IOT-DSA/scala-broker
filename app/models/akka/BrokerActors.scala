package models.akka

import akka.actor.ActorSystem
import akka.cluster.Cluster
import javax.inject.{ Inject, Singleton }
import models.akka.cluster.ClusteredDownstreamActor
import models.akka.local.LocalDownstreamActor

/**
 * A wrapper for essential actors to be started when the application starts.
 */
@Singleton
class BrokerActors @Inject() (actorSystem: ActorSystem, dslinkMgr: DSLinkManager) {
  import models.Settings._

  val (root, downstream) = if (actorSystem.hasExtension(Cluster))
    createClusteredActors
  else
    createLocalActors

  private def createLocalActors = {
    val root = actorSystem.actorOf(RootNodeActor.props, Nodes.Root)
    val downstream = actorSystem.actorOf(LocalDownstreamActor.props(dslinkMgr), Nodes.Downstream)
    (root, downstream)
  }

  private def createClusteredActors = {
    val root = RootNodeActor.singletonStart(actorSystem)
    val downstream = actorSystem.actorOf(ClusteredDownstreamActor.props(dslinkMgr), Nodes.Downstream)
    (root, downstream)
  }
}