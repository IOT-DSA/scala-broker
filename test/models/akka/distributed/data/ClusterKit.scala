package models.akka.distributed.data

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import com.typesafe.config.ConfigFactory
import models.api.DistributedNodesRegistry.AddNode
import models.api.{DSANode, DSANodeDescription, DistributedNodesRegistry}
import org.scalatest.GivenWhenThen
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._

trait ClusterKit { self: GivenWhenThen =>

  implicit val timeout = Timeout(5 seconds)
  implicit val ctx = scala.concurrent.ExecutionContext.global

  case class DDStuff(system:ActorSystem, cluster:Cluster, replicator:ActorRef)

  def withDistributedNodes(leftPort:String, rightPort:String)(action:(DSANode, DSANode) => Unit): Unit = {
    withDDStuff(leftPort){
      leftTools =>
        withDDStuff(rightPort){
          rightTools =>



            And("Distributed nodes pair created")
            val leftRegistry = leftTools.system.actorOf(DistributedNodesRegistry.props(leftTools.replicator, leftTools.cluster, leftTools.system), "left")
            val rightRegistry = rightTools.system.actorOf(DistributedNodesRegistry.props(rightTools.replicator, rightTools.cluster, rightTools.system), "right")

            val l = (leftRegistry ? AddNode(DSANodeDescription.init("data", Some("broker/dataRoot")))).mapTo[DSANode]
            val r = (rightRegistry ? AddNode(DSANodeDescription.init("data", Some("broker/dataRoot")))).mapTo[DSANode]

            val lrFuture = for {
              left <- l
              right <- r
            } yield (left, right)

            val arg = Await.result(lrFuture, 2 seconds)

            TimeUnit.SECONDS.sleep(2)

            action(arg._1, arg._2)

        }
    }
  }

  def withDistributedNodesExtended(leftPort:String, rightPort:String)(action:((DSANode, DDStuff), (DSANode, DDStuff)) => Unit): Unit = {
    withDDStuff(leftPort){
      leftTools =>
        withDDStuff(rightPort){
          rightTools =>


            And("Distributed nodes pair created")
            val leftRegistry = leftTools.system.actorOf(DistributedNodesRegistry.props(leftTools.replicator, leftTools.cluster, leftTools.system), "left")
            val rightRegistry = rightTools.system.actorOf(DistributedNodesRegistry.props(rightTools.replicator, rightTools.cluster, rightTools.system), "right")

            val l = (leftRegistry ? AddNode(DSANodeDescription.init("data", Some("broker/dataRoot")))).mapTo[DSANode]
            val r = (rightRegistry ? AddNode(DSANodeDescription.init("data", Some("broker/dataRoot")))).mapTo[DSANode]

            val lrFuture = for {
              left <- l
              right <- r
            } yield (left, right)

            val arg = Await.result(lrFuture, 2 seconds)

            TimeUnit.SECONDS.sleep(2)

            action((arg._1, leftTools), (arg._2, rightTools))

        }
    }
  }

  def withDDStuff(port: String)(action:DDStuff => Unit):Unit = {
    withSystem(port){ system =>
      val node = Cluster(system)
      And(s"cluster node started: ${node}")

      val replicator = DistributedData(system).replicator
      And(s"replicator created: $replicator")

      action(DDStuff(system, node, replicator))
    }
  }

  def withSystem(port: String)(action:ActorSystem => Unit): Unit = {
    val s = system(port)
    try{
      action(s)
      And(s"ActorSystem on port $port stopped")
      Await.result(s.terminate(), 30 seconds)
    } finally {
      s.terminate()
    }
  }

  def system(port: String) = {
    When(s"ActorSystem on port $port started")
    val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
      withFallback(ConfigFactory.load(
        ConfigFactory.parseString("""
            akka.loglevel = "DEBUG"
            akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
            akka.remote {
              netty.tcp {
                hostname = "127.0.0.1"
                port = 0
              }
            }
            akka.cluster {
              distributed-data {
                gossip-interval = 150 ms
                notify-subscribers-interval = 150 ms
              }
              seed-nodes = [
                "akka.tcp://DDTestClusterSystem@127.0.0.1:2555"]
              auto-down-unreachable-after = 10s
            }
            """)))

    // Create an Akka system
    ActorSystem("DDTestClusterSystem", config)
  }

}
