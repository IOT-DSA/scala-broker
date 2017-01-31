package models.actors

import org.scalatest.{ MustMatchers, WordSpecLike }

import com.typesafe.config.ConfigFactory

import akka.actor.{ ActorSystem, Props, actorRef2Scala }
import akka.testkit.TestKit
import javax.inject.{ Inject, Singleton }
import models.{ RequestEnvelope, Settings }
import models.rpc.{ AllowedMessage, InvokeRequest, PingMessage, RequestMessage, ResponseMessage }
import net.sf.ehcache.CacheManager
import play.api.Configuration
import play.api.cache.EhCacheApi

/**
 * ResponderActor test suite.
 */
class ResponderActorSpec extends TestKit(ActorSystem()) with WordSpecLike with MustMatchers {

  val settings = new Settings(new Configuration(ConfigFactory.load))
  val connInfo = ConnectionInfo("testDsId", false, true, "/downstream/link")
  val cache = new EhCacheApi(CacheManager.getInstance.addCacheIfAbsent("test"))
  val config = WebSocketActorConfig(connInfo, settings, cache)
  val router = new AkkaRouter(cache)

  val rspActor = system.actorOf(Props(new ResponderActor(testActor, config, router)))

  "ResponderActor" should {
    "send 'allowed' message on startup" in {
      expectMsg(AllowedMessage(true, settings.Salt))
    }
    "return ack for a response message" in {
      rspActor ! ResponseMessage(101, None, Nil)
      expectMsg(PingMessage(1, Some(101)))
    }
    "route requests to socket" in {
      rspActor ! RequestEnvelope("", "", List(InvokeRequest(102, "/downstream/link/abc")))
      expectMsg(RequestMessage(2, None, List(InvokeRequest(1, "/abc"))))
    }
  }
}