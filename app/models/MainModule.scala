package models

import scala.collection.Seq

import _root_.akka.actor.ActorSystem
import _root_.akka.cluster.Cluster
import javax.inject.{ Inject, Provider, Singleton }
import models.akka.{ BrokerActors, DSLinkManager }
import models.akka.cluster.ClusteredDSLinkManager
import models.akka.local.LocalDSLinkManager
import models.handshake.LocalKeys
import play.api.{ Configuration, Environment }
import play.api.inject.Module

/**
 * Provides module bindings.
 */
class MainModule extends Module {

  def bindings(environment: Environment, configuration: Configuration) = {
    Seq(
      bind[DSLinkManager].toProvider[DSLinkManagerProvider],
      bind[LocalKeys].to(LocalKeys.getFromClasspath("/keys")),
      bind[BrokerActors].toSelf.eagerly)
  }
}

/**
 * Provides an instance of [[DSLinkManager]] class.
 */
@Singleton
class DSLinkManagerProvider @Inject() (actorSystem: ActorSystem)
  extends Provider[DSLinkManager] {

  private val mgr = if (actorSystem.hasExtension(Cluster))
    new ClusteredDSLinkManager(false)(actorSystem)
  else
    new LocalDSLinkManager()(actorSystem)

  def get = mgr
}