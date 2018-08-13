package models


import scala.collection.Seq
import _root_.akka.actor.ActorSystem
import _root_.akka.cluster.Cluster
import com.romix.akka.serialization.kryo.ActorRefSerializer
import javax.inject.{Inject, Provider, Singleton}
import kamon.Kamon
import kamon.statsd.StatsDReporter
import kamon.system.SystemMetrics
//import kamon.zipkin.ZipkinReporter
import models.akka.{BrokerActors, DSLinkManager, DeadLettersGuard, SystemGuard}
import models.akka.cluster.ClusteredDSLinkManager
import models.akka.local.LocalDSLinkManager
import models.handshake.LocalKeys
import play.api.{Configuration, Environment}
import play.api.inject.{ApplicationLifecycle, Module}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Provides module bindings.
 */
class MainModule extends Module {

  def bindings(environment: Environment, configuration: Configuration) = {
    Seq(
      bind[DSLinkManager].toProvider[DSLinkManagerProvider],
      bind[LocalKeys].to(LocalKeys.getFromClasspath("/keys")),
      bind[BrokerActors].toSelf.eagerly,
      bind[SystemGuard].toSelf.eagerly,
      bind[StatsDConnection].toSelf.eagerly())
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

@Singleton
class StatsDConnection @Inject()(lifecycle: ApplicationLifecycle) {

  if(Settings.MetricsReporters.statsdConfigured)  Kamon.addReporter(new StatsDReporter())
//  if(Settings.MetricsReporters.zipkinConfigured)  Kamon.addReporter(new ZipkinReporter())

  SystemMetrics.startCollecting()

  lifecycle.addStopHook(() => {
    Future{
      SystemMetrics.stopCollecting()
    }(ExecutionContext.global)
  })

}