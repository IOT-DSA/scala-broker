package models.akka.distributed.data

import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, TypedActor}
import akka.testkit.TestProbe
import models.ResponseEnvelope
import models.api.{DSANodeDescription, DSAValueType, DistributedDSANode}
import models.api.DistributedDSANode.DistributedDSANodeData
import models.rpc.DSAValue
import models.rpc.DSAValue._
import org.scalatest.{GivenWhenThen, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.concurrent.Await

class DistributedNodeSpec extends WordSpecLike with ClusterKit
  with Matchers
  with GivenWhenThen {

  "Distributed data nodes" should {

    "change value, valueType, profile, displayName" in withDistributedNodes("2555", "2556") { case (left, right) =>
      TimeUnit.SECONDS.sleep(5)
      When("change value on String in first node")
      left.valueType = DSAValueType.DSAString
      left.value = "new value"
      And("profile")
      left.profile = "someProfile"
      And("display name")
      left.displayName = "Clark Kent"
      TimeUnit.MILLISECONDS.sleep(2000)
      Then("value should be changed in right")
      val rightValue = Await.result(right.value, 2 seconds)
      val rightType = Await.result(right.valueType, 2 seconds)



      Await.result(right.displayName, 2 seconds) shouldBe "Clark Kent"
      right.profile shouldBe "someProfile"
      rightType shouldBe DSAValueType.DSAString
      rightValue shouldBe StringValue("new value")

      When("change value on Int in first node")
      left.valueType = DSAValueType.DSANumber
      left.value = NumericValue(99999)
      TimeUnit.MILLISECONDS.sleep(500)
      Then("value should be changed in right")
      val rightValue2 = Await.result(right.value, 2 seconds)
      val rightType2 = Await.result(right.valueType, 2 seconds)

      rightType2 shouldBe DSAValueType.DSANumber
      rightValue2 shouldBe NumericValue(99999)

    }

    "add delete and change attributes" in withDistributedNodes("2555", "2556") { case (left, right) =>

      When("create attribute in first node")
      left.addAttributes(("@first" -> "firstVal"), ("second" -> NumericValue(123)))

      TimeUnit.MILLISECONDS.sleep(500)

      Then("other node should have same")
      Await.result(right.attribute("@first"), 1 second).get shouldBe StringValue("firstVal")
      Await.result(right.attribute("@second"), 1 second).get shouldBe NumericValue(123)

      val leftAttr = Await.result(left.attributes, 2 seconds)
      val rightAttr = Await.result(right.attributes, 2 seconds)

      leftAttr.toList shouldBe rightAttr.toList

      When("change attribute in first node")
      left.addAttributes(("@first" -> "changedVal"))
      TimeUnit.MILLISECONDS.sleep(500)

      Then("other node should have same")
      Await.result(right.attribute("@first"), 1 second).get shouldBe StringValue("changedVal")

      When("remove attribute")
      left.removeAttribute("@first")

      TimeUnit.MILLISECONDS.sleep(500)

      Then("attribute should be droped to initial value")
      Await.result(right.attribute("@first"), 1 second) shouldBe None

    }

    "add delete and change configs" in withDistributedNodes("2555", "2556") { case (left, right) =>

      When("create config in first node")
      left.addConfigs(("$first" -> "firstVal"), ("second" -> NumericValue(123)))

      TimeUnit.MILLISECONDS.sleep(500)

      Then("other node should have same")
      Await.result(right.config("$first"), 1 second).get shouldBe StringValue("firstVal")
      Await.result(right.config("$second"), 1 second).get shouldBe NumericValue(123)

      val leftAttr = Await.result(left.configs, 2 seconds)
      val rightAttr = Await.result(right.configs, 2 seconds)

      leftAttr.toList shouldBe rightAttr.toList

      When("change config in first node")
      left.addConfigs(("$first" -> "changedVal"))

      TimeUnit.MILLISECONDS.sleep(500)

      Then("other node should have same")
      Await.result(right.config("$first"), 1 second).get shouldBe StringValue("changedVal")

      When("remove config")
      left.removeConfig("$first")

      TimeUnit.MILLISECONDS.sleep(500)

      Then("config should be dropped to initial value")
      Await.result(right.config("$first"), 1 second) shouldBe None

    }

    "create and delete children" in withDistributedNodes("2555", "2556") { case (left, right) =>

      import DSAValue._

      val data:Seq[(String, DSAVal)] = Seq(("$is"->  "broker/dataNode"), ("@attr" -> "attr"))

      val child1 = Await.result(left.addChild("child1", data:_*), 1 second)
      val child2 = Await.result(left.addChild("child2"), 1 second)
      val grandChild = Await.result(child1.addChild("grandChild"), 1 second)

      TimeUnit.SECONDS.sleep(3)

      val leftChildren = Await.result(left.children, 1 second)
      val rightChildren = Await.result(right.children, 1 second)


      rightChildren.get("child1").isDefined shouldBe true
      rightChildren.get("child2").isDefined shouldBe true

      rightChildren("child1").parent shouldBe Some(right)

      rightChildren.map(_._1).toSet shouldBe leftChildren.map(_._1).toSet

      child1.profile shouldBe "broker/dataNode"
      rightChildren("child1").profile shouldBe "broker/dataNode"

      Await.result(rightChildren("child1").children, 1 second)("grandChild").parent.get shouldBe rightChildren.get("child1").get

      right.removeChild("child1")

      TimeUnit.SECONDS.sleep(3)

      Await.result(right.child("child1"), 1 second) shouldBe None
      Await.result(left.child("child1"), 1 second) shouldBe None
    }

    "description initialized properly" in {

      val conf:DSAVal = "confValue"
      val attr:DSAVal = "attrValue"

      val map:Map[String, DSAVal] = Map(
        "$conf" -> conf,
        "@attr" -> attr
      )

      val data = DistributedDSANode.initialData(DSANodeDescription("/somePath", map), "", "name")

      data.configs.get("$conf") shouldBe Some(conf)
      data.attributes.get("@attr") shouldBe Some(attr)
    }

    "send subscriptions to local actors on value update" in withDistributedNodesExtended("2555", "2556") {
      case ((left, lTools), (right, rTools)) =>

        val leftProbe = TestProbe("probe1")(lTools.system)
        val rightProbe = TestProbe("probe1")(rTools.system)

        left.subscribe(1, leftProbe.ref)
        right.subscribe(2, rightProbe.ref)

        TimeUnit.SECONDS.sleep(1)

        left.value = "CHANGED!!!"

        val notification1 = leftProbe.receiveOne(2 seconds)
        leftProbe.expectNoMessage(2 seconds)
        val notification2 = rightProbe.receiveOne(2 seconds)
        rightProbe.expectNoMessage(2 seconds)

        left.unsubscribe(1)
        right.unsubscribe(2)

        TimeUnit.SECONDS.sleep(1)

        right.value = "CHANGED2"

        leftProbe.expectNoMessage(2 seconds)
        rightProbe.expectNoMessage(2 seconds)
    }

    "send list notifications to local address on child/attribute/config updates" in withDistributedNodesExtended("2555", "2556") {
      case ((left, lTools), (right, rTools)) =>

        val leftProbe = TestProbe("probe1")(lTools.system)
        val rightProbe = TestProbe("probe1")(rTools.system)

        left.list(1, leftProbe.ref)
        right.list(2, rightProbe.ref)

        TimeUnit.SECONDS.sleep(1)

        left.addChild("childListTestNode")
        left.addConfigs("config" -> StringValue("!!!"))
        left.addAttributes("attribute" -> StringValue("!!!"))

        TimeUnit.SECONDS.sleep(1)

        def extractUpdates(envelops: Seq[ResponseEnvelope]) = for {
          e <- envelops
          resp <- e.responses
          update <- resp.updates.getOrElse(List())
        } yield update

        val notifications2 = extractUpdates(rightProbe.expectMsgAllClassOf(2 seconds, classOf[ResponseEnvelope]))
        val notifications1 = extractUpdates(leftProbe.expectMsgAllClassOf(2 seconds, classOf[ResponseEnvelope]))

        TimeUnit.SECONDS.sleep(1)

        notifications1.isEmpty shouldBe false
        notifications2.isEmpty shouldBe false

        left.removeAttribute("@attribute")
        TimeUnit.SECONDS.sleep(1)
        left.removeConfig("$config")
        TimeUnit.SECONDS.sleep(1)
        left.removeChild("childListTestNode")

        val deleteNat2 = extractUpdates(rightProbe.expectMsgAllClassOf(4 seconds, classOf[ResponseEnvelope]))
        val deleteNat1 = extractUpdates(leftProbe.expectMsgAllClassOf(4 seconds, classOf[ResponseEnvelope]))

        deleteNat1.isEmpty shouldBe false
        deleteNat2.isEmpty shouldBe false
    }
  }


}
