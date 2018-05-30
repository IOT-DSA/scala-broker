package models.akka.distributed.data

import java.util.concurrent.TimeUnit

import models.api.DSAValueType
import models.rpc.DSAValue._
import org.scalatest.{GivenWhenThen, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.concurrent.Await

class DistributedNodeSpec extends WordSpecLike with ClusterKit
  with Matchers
  with GivenWhenThen {

  "Distributed data nodes" should {

//    "change value, valueType, profile, displayName" in withDistributedNodes("2555", "2556") { case (left, right) =>
//
//      When("change value on String in first node")
//      left.valueType = DSAValueType.DSAString
//      left.value = "new value"
//      And("profile")
//      left.profile = "someProfile"
//      And("display name")
//      left.displayName = "Clark Kent"
//      TimeUnit.MILLISECONDS.sleep(500)
//      Then("value should be changed in right")
//      val rightValue = Await.result(right.value, 2 seconds)
//      val rightType = Await.result(right.valueType, 2 seconds)
//
//      Await.result(right.displayName, 2 seconds) shouldBe "Clark Kent"
//      right.profile shouldBe "someProfile"
//      rightType shouldBe DSAValueType.DSAString
//      rightValue shouldBe StringValue("new value")
//
//      When("change value on Int in first node")
//      left.valueType = DSAValueType.DSANumber
//      left.value = 99999
//      TimeUnit.MILLISECONDS.sleep(500)
//      Then("value should be changed in right")
//      val rightValue2 = Await.result(right.value, 2 seconds)
//      val rightType2 = Await.result(right.valueType, 2 seconds)
//
//      rightType2 shouldBe DSAValueType.DSANumber
//      rightValue2 shouldBe NumericValue(99999)
//
//    }
//
//    "add delete and change attributes" in withDistributedNodes("2555", "2556") { case (left, right) =>
//
//      When("create attribute in first node")
//      left.addAttributes(("@first" -> "firstVal"), ("second" -> 123))
//
//      TimeUnit.MILLISECONDS.sleep(500)
//
//      Then("other node should have same")
//      Await.result(right.attribute("@first"), 1 second).get shouldBe StringValue("firstVal")
//      Await.result(right.attribute("@second"), 1 second).get shouldBe NumericValue(123)
//
//      val leftAttr = Await.result(left.attributes, 2 seconds)
//      val rightAttr = Await.result(right.attributes, 2 seconds)
//
//      leftAttr.toList shouldBe rightAttr.toList
//
//      When("remove attribute")
//      left.removeAttribute("@first")
//
//      TimeUnit.MILLISECONDS.sleep(500)
//
//      Then("attribute should be deleted from other node")
//      Await.result(right.attribute("@first"), 1 second) shouldBe None
//
//    }
//
//    "add delete and change configs" in withDistributedNodes("2555", "2556") { case (left, right) =>
//
//      When("create attribute in first node")
//      left.addConfigs(("$first" -> "firstVal"), ("second" -> 123))
//
//      TimeUnit.MILLISECONDS.sleep(500)
//
//      Then("other node should have same")
//      Await.result(right.config("$first"), 1 second).get shouldBe StringValue("firstVal")
//      Await.result(right.config("$second"), 1 second).get shouldBe NumericValue(123)
//
//      val leftAttr = Await.result(left.configs, 2 seconds)
//      val rightAttr = Await.result(right.configs, 2 seconds)
//
//      leftAttr.toList shouldBe rightAttr.toList
//
//      When("remove attribute")
//      left.removeAttribute("$first")
//
//      TimeUnit.MILLISECONDS.sleep(500)
//
//      Then("attribute should be deleted from other node")
//      Await.result(right.attribute("$first"), 1 second) shouldBe None
//
//    }

    "should create and delete children" in withDistributedNodes("2555", "2556") { case (left, right) =>

      val child1 = Await.result(left.addChild("child1"), 1 second)
      val child2 = Await.result(left.addChild("child2"), 1 second)
      val grandChild = Await.result(child1.addChild("child2"), 1 second)

      TimeUnit.MICROSECONDS.sleep(500)

      val rightChildren = Await.result(right.children, 1 second)

//      rightChildren should have "child1"
//      rightChildren should have "child2"

      rightChildren("child1").parent shouldBe Some(right)


    }

  }


}
