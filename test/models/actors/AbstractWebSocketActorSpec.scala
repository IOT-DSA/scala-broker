package models.actors

import org.scalatest.{ Finders, MustMatchers, WordSpecLike }

import akka.actor.{ ActorRef, ActorSystem, PoisonPill, Props, actorRef2Scala }
import akka.testkit.{ TestKit, TestProbe }
import models.{ AllowedMessage, PingMessage }
import net.sf.ehcache.CacheManager
import play.api.cache.{ CacheApi, EhCacheApi }

/**
 * AbstractWebSocketActor test suite.
 */
class AbstractWebSocketActorSpec extends TestKit(ActorSystem()) with WordSpecLike with MustMatchers {
  import AbstractWebSocketActorSpec._

  val connInfo = ConnectionInfo("testDsId", true, true, "/path")
  val cache = new EhCacheApi(CacheManager.getInstance.addCacheIfAbsent("test"))
  val wsActor = system.actorOf(Props(new TestWSActor(testActor, connInfo, cache)))

  "AbstractWebSocketActor" should {
    "send 'allowed' message on startup" in {
      expectMsg(AllowedMessage(true, Salt))
    }
    "save its reference to cache on startup" in {
      cache.get[ActorRef]("/path") mustBe Some(wsActor)
    }
    "return ack for a ping message" in {
      wsActor ! PingMessage(101)
      expectMsg(PingMessage(1, Some(101)))
      wsActor ! PingMessage(102)
      expectMsg(PingMessage(2, Some(102)))
    }
    "remove itself from cache on close" in {
      val probe = TestProbe()
      probe watch wsActor
      wsActor ! PoisonPill
      probe.expectTerminated(wsActor)
      cache.get[ActorRef]("/path") mustBe None
    }
  }
}

/**
 * Provides test actor class extending AbstractWebSocketActor.
 */
object AbstractWebSocketActorSpec {

  class TestWSActor(out: ActorRef, connInfo: ConnectionInfo, cache: CacheApi)
    extends AbstractWebSocketActor(out, connInfo, cache)

}