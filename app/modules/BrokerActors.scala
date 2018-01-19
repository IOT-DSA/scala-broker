package modules

import akka.actor.ActorSystem
import akka.cluster.Cluster
import javax.inject.{ Inject, Singleton }
import models.akka.{ DSLinkManager, RootNodeActor }
import models.akka.cluster.{ ClusteredDSLinkManager, ClusteredDownstreamActor }
import models.akka.local.LocalDownstreamActor
import models.metrics.EventDaos

/**
 * A wrapper for essential actors to be started when the application starts.
 */
@Singleton
class BrokerActors @Inject() (actorSystem: ActorSystem, dslinkMgr: DSLinkManager, eventDaos: EventDaos) {
  import models.Settings._

  val (root, downstream) = if (actorSystem.hasExtension(Cluster))
    createClusteredActors
  else
    createLocalActors

  private def createLocalActors = {
    val root = actorSystem.actorOf(RootNodeActor.props, Nodes.Root)
    val downstream = actorSystem.actorOf(LocalDownstreamActor.props(dslinkMgr, eventDaos), Nodes.Downstream)
    (root, downstream)
  }

  private def createClusteredActors = {
    val root = RootNodeActor.singletonStart(actorSystem)
    // TODO will get rid of `asInstanceOf` after refactoring DSLinkManager
    val downstream = actorSystem.actorOf(ClusteredDownstreamActor.props(
      dslinkMgr.asInstanceOf[ClusteredDSLinkManager], eventDaos), Nodes.Downstream)
    (root, downstream)
  }
}