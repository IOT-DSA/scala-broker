package it

import java.util.concurrent.TimeUnit

import infrastructure.{LoggingBridge, SingleNodeIT}
import infrastructure.tester.RequesterAndResponder
import org.dsa.iot.dslink.node.value.{Value, ValueType}
import org.dsa.iot.dslink.util.log.LogManager
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._

class QoSSpec extends FlatSpec
  with SingleNodeIT
  with GivenWhenThen
  with Matchers
  with RequesterAndResponder {

  LogManager.setBridge(new LoggingBridge)

  "QoS" should "save level 2 subscriptions messages in case of disconnect" in {
    withResponder(name = "QoSResponder") {
      responder =>

        responder.createNode(new Value(0), ValueType.NUMBER, "subscribeOnMe", "subscribeOnMe!!! Please")

        withRequester(name = "QoSRequester1") {
          requester =>
            val subscription = requester.subscribe("downstream/QoSResponder/subscribeOnMe")

            Future {
              0 to 10 map { i =>
                requester.set("/downstream/QoSResponder/subscribeOnMe", new Value(i)).block()
                log.info(s"value changed to $i")
              }
            }
        }

        withRequester(name = "QoSRequester1") {
          requester =>

            var items = List[Any]()

            val subscription = requester.subscribe("downstream/QoSResponder/subscribeOnMe")

            subscription
              .map(value => {
                items = value :: items
                log.info(s"add item to list ${value}")
              })
              .subscribe()


            Future {
              11 to 20 map { i =>
                requester.set("/downstream/QoSResponder/subscribeOnMe", new Value(i)).block()
                log.info(s"value changed to $i")
              }
            }

            TimeUnit.SECONDS.sleep(3)

        }
    }

  }

}
