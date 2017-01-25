package models.actors

import org.scalatest.{ MustMatchers, WordSpecLike }

import akka.actor.{ ActorSystem, Props, actorRef2Scala }
import akka.testkit.TestKit
import models._
import net.sf.ehcache.CacheManager
import play.api.cache.EhCacheApi
import play.api.Configuration
import com.typesafe.config.ConfigFactory

/**
 * ResponderActor test suite.
 */
class ResponderActorSpec extends TestKit(ActorSystem()) with WordSpecLike with MustMatchers {

  val settings = new Settings(new Configuration(ConfigFactory.load))
  val connInfo = ConnectionInfo("testDsId", false, true, "/downstream/link")
  val cache = new EhCacheApi(CacheManager.getInstance.addCacheIfAbsent("test"))

  val rspActor = system.actorOf(Props(new ResponderActor(testActor, settings, connInfo, cache)))

  "ResponderActor" should {
    "send 'allowed' message on startup" in {
      expectMsg(AllowedMessage(true, settings.Salt))
    }
    "return ack for a response message" in {
      rspActor ! ResponseMessage(101, None, Nil)
      expectMsg(PingMessage(1, Some(101)))
    }
    "route requests to socket" in {
      rspActor ! RequestEnvelope(InvokeRequest(102, "/downstream/link/abc"))
      expectMsg(RequestMessage(2, None, List(InvokeRequest(1, "/abc"))))
    }
  }
}