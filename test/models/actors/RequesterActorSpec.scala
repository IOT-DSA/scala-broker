package models.actors

import org.scalatest.{ MustMatchers, WordSpecLike }

import com.typesafe.config.ConfigFactory

import akka.actor.{ ActorSystem, Props, actorRef2Scala }
import akka.testkit.{ TestKit, TestProbe }
import models.{ RequestEnvelope, ResponseEnvelope, Settings }
import models.rpc._
import net.sf.ehcache.CacheManager
import play.api.Configuration
import play.api.cache.EhCacheApi

/**
 * RequesterActor test suite.
 */
class RequesterActorSpec extends TestKit(ActorSystem()) with WordSpecLike with MustMatchers {

  val settings = new Settings(new Configuration(ConfigFactory.load))
  val connInfo = ConnectionInfo("testDsId", true, false, "/reqPath")
  val cache = new EhCacheApi(CacheManager.getInstance.addCacheIfAbsent("test"))
  val config = WebSocketActorConfig(connInfo, settings, cache)
  val router = new AkkaRouter(cache)

  val sysProbe = TestProbe()
  cache.set("/sys", sysProbe.ref)
  
  val reqActor = system.actorOf(Props(new RequesterActor(testActor, config, router)))

  "RequesterActor" should {
    "send 'allowed' message on startup" in {
      expectMsg(AllowedMessage(true, settings.Salt))
    }
    "return ack for a request message" in {
      reqActor ! RequestMessage(101, None, Nil)
      expectMsg(PingMessage(1, Some(101)))
    }
    "route requests to appropriate targets" in {
      reqActor ! RequestMessage(104, None, List(ListRequest(111, "/sys/abc")))
      expectMsg(PingMessage(2, Some(104)))
      sysProbe.expectMsg(RequestEnvelope("/reqPath", "/sys", false, List(ListRequest(111, "/sys/abc"))))
    }
    "route responses to socket" in {
      reqActor ! ResponseEnvelope("", "", List(DSAResponse(101, Some(StreamState.Open))))
      expectMsg(ResponseMessage(3, None, List(DSAResponse(101, Some(StreamState.Open)))))
    }
  }
}