package modules

import akka.actor.ActorSystem
import akka.cluster.Cluster
import javax.inject.{ Inject, Provider, Singleton }
import models.akka.DSLinkManager
import models.akka.cluster.ClusteredDSLinkManager
import models.akka.local.LocalDSLinkManager
import models.metrics.{ DSLinkEventDao, MetricDao }
import play.api.{ Configuration, Environment }
import play.api.inject.Module

/**
 * Provides module bindings.
 */
class MainModule extends Module {

  def bindings(environment: Environment, configuration: Configuration) = Seq(
    bind[DSLinkManager].toProvider[DSLinkManagerProvider],
    bind[DSLinkEventDao].to(MetricDao.dslinkEventDao))
}

/**
 * Provides an instance of [[DSLinkManager]] class.
 */
@Singleton
class DSLinkManagerProvider @Inject() (implicit actorSystem: ActorSystem)
  extends Provider[DSLinkManager] {

  private val mgr = if (actorSystem.hasExtension(Cluster))
    new ClusteredDSLinkManager(false)
  else
    new LocalDSLinkManager

  def get = mgr
}