package models.actors

import org.scalatest.{ Finders, Matchers, WordSpecLike }

import akka.actor.{ ActorSystem, actorRef2Scala }
import akka.testkit.TestKit
import play.api.libs.json.Json
import play.api.libs.json.Json.toJsFieldJsValueWrapper

/**
 * WebSocket actor test suite.
 */
class WebSocketActorSpec extends TestKit(ActorSystem()) with WordSpecLike with Matchers {

  val wsActor = system.actorOf(WebSocketActor.props(testActor))

  "WebSocketActor" should {
    "return 'allowed' response on empty msg" in {
      wsActor ! Json.obj()
      expectMsg(Json.obj("allowed" -> true, "salt" -> 1234))
    }
    "return ack for a valid message" in {
      wsActor ! Json.obj("msg" -> 101)
      expectMsg(Json.obj("msg" -> 1, "ack" -> 101))
      wsActor ! Json.obj("msg" -> 102)
      expectMsg(Json.obj("msg" -> 2, "ack" -> 102))
    }
  }
}