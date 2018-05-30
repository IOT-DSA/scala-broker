package models.akka.distributed.data

import java.util.concurrent.TimeUnit

import models.api.{DSANode, DistributedNodesRegistry}
import org.scalatest.{GivenWhenThen, Matchers, WordSpecLike}
import akka.pattern.ask
import models.api.DistributedNodesRegistry.{AddNode, GetNodes, RemoveNode}

import scala.concurrent.Await
import scala.concurrent.duration._


class DistributedStateRegistrySpec extends WordSpecLike with ClusterKit
  with Matchers
  with GivenWhenThen {

  "distributed data registry" should {
    "create create and delete distributed DSANodes" in withDDStuff("2555"){
      case DDStuff(system1, node1, replicator1) =>
      withDDStuff("2556"){
        case DDStuff(system2, node2, replicator2) =>

          val registry = system1.actorOf(DistributedNodesRegistry.props(replicator1, node1, system1), "registry")
          val registryReplica = system1.actorOf(DistributedNodesRegistry.props(replicator2, node2, system2), "registryReplica")

          When("Parent \"/data\" dsnode created")
          val created = Await.result((registry ? AddNode("/data")).mapTo[DSANode], 2 seconds)

          And("Child \"/data/child\" dsnode created")
          val createdChild = Await.result((registry ? AddNode("/data/child")).mapTo[DSANode], 2 seconds)

          TimeUnit.MILLISECONDS.sleep(500)

          val replicaNodes = Await.result((registryReplica ? GetNodes()).mapTo[Map[String, DSANode]], 2 seconds)

          Then("parent should be added to child node")
          createdChild.parent.isDefined shouldBe true
          createdChild.parent.get shouldBe created

          And("Child should be listed in parent node")
          val ch = Await.result(created.child("child"), 2 second)

          ch.isDefined shouldBe true
          ch.get shouldBe createdChild

          Await.result(created.child("child"), 2 seconds).get shouldBe createdChild

          And("all nodes should be created in replicated registry")
          replicaNodes.map(_._1).toSet shouldBe Set("/data", "/data/child")

          Await.result(replicaNodes("/data").child("child"), 2 seconds).get shouldBe replicaNodes("/data/child")
          replicaNodes("/data/child").parent.get shouldBe replicaNodes("/data")

          val removed = (registry ? RemoveNode("/data")).mapTo[Set[String]]
          Await.result(removed, 2 seconds) shouldBe Set("/data", "/data/child")

          Await.result((registry ? GetNodes()).mapTo[Map[String, DSANode]], 2 seconds).isEmpty shouldBe true

          TimeUnit.MILLISECONDS.sleep(500)

          Await.result((registryReplica ? GetNodes()).mapTo[Map[String, DSANode]], 2 seconds).isEmpty shouldBe true
      }
    }

  }





}
