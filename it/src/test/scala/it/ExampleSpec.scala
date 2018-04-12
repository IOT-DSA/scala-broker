package it


import infrastructure.{LoggingBridge, SingleNodeIT}
import infrastructure.tester.RequesterAndResponder
import org.dsa.iot.dslink.node.value.Value
import org.dsa.iot.dslink.util.json.JsonObject
import org.dsa.iot.dslink.util.log.LogManager
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}

import scala.concurrent.duration._
import scala.collection.JavaConverters._

/**
  * Example IT spec. Basically:
  * 1. Starts docker container with scala broker (:latest image)
  * 2. creates requester and responder, connects it to broker
  * 3. Adds node to responder via Invoke method
  * 4. gets list of child nodes
  * 5. changes value of node
  */
class ExampleSpec extends FlatSpec
  with SingleNodeIT
  with GivenWhenThen
  with Matchers
  with RequesterAndResponder {

  LogManager.setBridge(new LoggingBridge)

  "it test" should "run" in {

    When("it starts")
    Then("docker container should be started")

    And("Responder instance should be initialized")
    withRequesterAndResponder(
      requesterName = "requester1",
      responderName = "responder1"
    ){ (responder, requester )=>

      When("We add node to responder by Action invoke")
      val data = new JsonObject()
      val newVal = "new_string_value"
      data.put("value", newVal)
      data.put("type", "STRING")
      data.put("id", "new_string")
      data.put("name", "Dmitry")

      val invokeResponse = requester
        .invoke("/downstream/responder1/Create_Node", data)
          .blockFirst(3 second)

      Then("it should be created successfully")
      invokeResponse.get.getState.name() shouldBe "CLOSED"
      invokeResponse.get.getError shouldBe null

      val nodeList = requester.list("/downstream/responder1")
        .blockFirst(3 seconds)

      And("it should present in nodes list")
      nodeList.get.getNode.getChildren.entrySet()
        .asScala
        .filter(_.getKey == "new_string")
        .headOption.isDefined shouldBe true

      When("We subscribe on node value changes")
      val subscription = requester.subscribe("/downstream/responder1/new_string")

      And("set new value")
      val setResult = requester.set("/downstream/responder1/new_string", new Value("changed"))
        .block(3 seconds)

      Then("we should get new value from subscription")
      val subs = subscription
        .filter(_.getValue.getString == "changed")
        .blockFirst(3 seconds)

      subs.get.getValue.getString shouldBe "changed"
      subs.get.getPath shouldBe "/downstream/responder1/new_string"
      setResult.getError shouldBe null

    }
  }

}
