package models.akka.distributed.data

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import com.typesafe.config.ConfigFactory
import models.api.{DSANode, DistributedNodesRegistry}
import org.scalatest.{GivenWhenThen, Matchers, WordSpecLike}
import akka.pattern.ask
import akka.util.Timeout
import models.api.DistributedNodesRegistry.{AddNode, GetNodes}

import scala.concurrent.Await
import scala.concurrent.duration._


class DataStateKeeperSpec extends WordSpecLike
  with Matchers
  with GivenWhenThen {

  "distributed data registry" should {

    val system1 = system("2551")
    val replicator1 = DistributedData(system1).replicator
    val node1 = Cluster(system1)

    val system2 = system("2552")
    val replicator2 = DistributedData(system2).replicator
    val node2 = Cluster(system2)

    val registry = system1.actorOf(DistributedNodesRegistry.props(replicator1, node1, system1), "registry")
    val registryReplica = system1.actorOf(DistributedNodesRegistry.props(replicator2, node2, system2), "registryReplica")

    "create distributed DSNode in every instance" in {

      implicit val timeout = Timeout(5 seconds)

      val created = Await.result((registry ? AddNode("/data")).mapTo[DSANode], 2 seconds)
      val createdChild = Await.result((registry ? AddNode("/data/child")).mapTo[DSANode], 2 seconds)
      createdChild.addConfigs(("$some" -> 123))
      createdChild.addAttributes(("@attr" -> 123332))
      createdChild.value = "Changed"
      val created2 = Await.result((registry ? AddNode("/data2")).mapTo[DSANode], 2 seconds)

      TimeUnit.SECONDS.sleep(2)

      val pathsOfReplica = Await.result((registryReplica ? GetNodes()).mapTo[Set[String]], 2 seconds)

      createdChild.parent.isDefined shouldBe true
      createdChild.parent.get shouldBe created

      Await.result(created.child("child"), 2 seconds).get shouldBe createdChild

      TimeUnit.SECONDS.sleep(5)

      pathsOfReplica shouldBe Set("/data", "/data/child", "/data2")
    }

  }



  def system(port: String) = {
    val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
      withFallback(ConfigFactory.load(
        ConfigFactory.parseString("""
            akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
            akka.remote {
              netty.tcp {
                hostname = "127.0.0.1"
                port = 0
              }
            }
            akka.cluster {
              seed-nodes = [
                "akka.tcp://DDTestClusterSystem@127.0.0.1:2551"]
              auto-down-unreachable-after = 10s
            }
            """)))

    // Create an Akka system
    ActorSystem("DDTestClusterSystem", config)
  }

}
