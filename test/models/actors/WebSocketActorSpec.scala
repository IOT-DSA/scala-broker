package models.actors

import org.scalatest.{ Finders, Matchers, WordSpecLike }

import akka.actor.{ ActorSystem, actorRef2Scala }
import akka.testkit.TestKit
import javax.inject.{ Inject, Singleton }
import models._
import net.sf.ehcache.CacheManager
import play.api.cache.EhCacheApi

/**
 * WebSocket actor test suite.
 */
class WebSocketActorSpec extends TestKit(ActorSystem()) with WordSpecLike with Matchers {
  import StreamState._
  
  val connInfo = ConnectionInfo("testDsId", true, true, "/path")
  
  val cache = new EhCacheApi(CacheManager.getInstance.addCacheIfAbsent("test"))
  cache.set(connInfo.dsId, connInfo)

  val wsConfig = WSActorConfig(testActor, connInfo.dsId, cache)
  val wsActor = system.actorOf(WebSocketActor.props(wsConfig))

  "WebSocketActor" should {
    "send 'allowed' message on startup" in expectMsg(AllowedMessage(true, 1234))
    "return ack for a ping message" in {
      wsActor ! PingMessage(101)
      expectMsg(PingMessage(1, Some(101)))
      wsActor ! PingMessage(102)
      expectMsg(PingMessage(2, Some(102)))
    }
    "return ack for a request message" in {
      wsActor ! RequestMessage(103, None, Nil)
      expectMsg(PingMessage(3, Some(103)))
      wsActor ! RequestMessage(104, None, List(ListRequest(111, "/sys")))
      expectMsg(PingMessage(4, Some(104)))
    }
    "return ack for a response message" in {
      wsActor ! ResponseMessage(105, None, Nil)
      expectMsg(PingMessage(5, Some(105)))
      wsActor ! ResponseMessage(106, None, List(DSAResponse(111, StreamState.Closed)))
      expectMsg(PingMessage(6, Some(106)))
    }
  }
}