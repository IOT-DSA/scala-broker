package models.akka.cluster

import com.typesafe.config.ConfigFactory

import akka.actor.ActorSystem
import models.Settings

/**
 * Broker backend application. Joins Akka cluster with "backend" role using
 * `backend.conf` configuration file.
 */
object BrokerBackend extends App {

  val port = args.headOption getOrElse "0"

  val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port)
    .withFallback(ConfigFactory.load("backend.conf"))

  println("Starting DSA Backend Broker...")

  val systemName = config.getString("play.akka.actor-system")
  implicit val system = ActorSystem(systemName, config.resolve)

  // start Backend
  system.actorOf(BackendActor.props, "backend")

  // start DSLinks shard region
  DSLinkActor.regionStart

  // start Root node 
  system.actorOf(RootNodeActor.props, Settings.Nodes.Root)

  sys.addShutdownHook {
    system.terminate
    println(s"DSA Backend broker stopped. The uptime time is ${system.uptime} seconds.")
  }

  println(s"The DSA Backend Broker started at ${new java.util.Date(system.startTime)}. Press Ctrl+C to stop it...")
}