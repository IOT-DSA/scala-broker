package models.akka

import com.typesafe.config.ConfigFactory

import akka.actor.{ ActorSystem, Props, actorRef2Scala }
import akka.pattern.ask
import akka.testkit.{ TestKit, TestProbe }
import akka.util.Timeout
import models.Settings

/**
 * FrontendActor test suite.
 */
class FrontendActorSpec extends AbstractActorSpec {
  import BackendActor._
  import Messages._

  implicit val timeout = Timeout(Settings.QueryTimeout)

  val backendSystem1 = {
    val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=2551").withFallback(ConfigFactory.load("backend.conf"))
    val systemName = config.getString("play.akka.actor-system")
    ActorSystem(systemName, config.resolve)
  }

  val backendSystem2 = {
    val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=0").withFallback(ConfigFactory.load("backend.conf"))
    val systemName = config.getString("play.akka.actor-system")
    ActorSystem(systemName, config.resolve)
  }

  val frontendSystem = {
    val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=0").withFallback(ConfigFactory.load("frontend.conf"))
    val systemName = config.getString("play.akka.actor-system")
    ActorSystem(systemName, config.resolve)
  }

  override def afterAll = {
    super.afterAll
    TestKit.shutdownActorSystem(frontendSystem)
    TestKit.shutdownActorSystem(backendSystem2)
    TestKit.shutdownActorSystem(backendSystem1)
  }

  "Local FrontendActor" should {
    val backend = TestProbe("backend")(system)
    val frontend = system.actorOf(FrontendActor.props, "frontend")
    "fail when no backend is present" in {
      val future = frontend ? GetDSLinkStats
      future.failed.futureValue mustBe an[IllegalStateException]
    }
    "register backends" in {
      backend.send(frontend, RegisterBackend)
      whenReady((frontend ? GetBrokerInfo).mapTo[BrokerInfo]) { info =>
        info.backends mustBe Seq(backend.ref.path)
        info.clusterInfo mustBe None
      }
    }
    "retrieve dslink stats" in {
      frontend ! GetDSLinkStats
      backend.expectMsg(GetDSLinkStats)
      val stats = DSLinkNodeStats(backend.ref.path.address, 1, 2, 3, 0, 1, 2)
      backend.reply(stats)
      expectMsg(DSLinkStats(Map(backend.ref.path.address -> stats)))
    }
    "search dslinks" in {
      frontend ! FindDSLinks("abc.*", 10, 5)
      backend.expectMsg(FindDSLinks("abc.*", 10, 5))
      backend.reply(List("abc1", "abc2", "abc3"))
      expectMsg(List("abc1", "abc2", "abc3"))
    }
    "remove disconnected dslinks" in {
      frontend ! RemoveDisconnectedDSLinks
      backend.expectMsg(RemoveDisconnectedDSLinks)
    }
  }

  "Cluster FrontendActor" should {
    val backend1 = TestProbe("backend")(backendSystem1)
    val backend2 = TestProbe("backend")(backendSystem2)
    val frontend = frontendSystem.actorOf(FrontendActor.props, "frontend")
    "fail when no backend is present" in {
      val future = frontend ? GetDSLinkStats
      future.failed.futureValue mustBe an[IllegalStateException]
    }
    "register backends" in {
      backend1.send(frontend, RegisterBackend)
      backend2.send(frontend, RegisterBackend)
      whenReady((frontend ? GetBrokerInfo).mapTo[BrokerInfo]) { info =>
        info.backends.toSet mustBe Set(backend1.ref.path, backend2.ref.path)
        info.clusterInfo mustBe defined
      }
    }
    "retrieve dslink stats" in {
      frontend ! GetDSLinkStats
      backend1.expectMsg(GetDSLinkStats)
      val stats1 = DSLinkNodeStats(backend1.ref.path.address, 1, 2, 3, 0, 1, 2)
      backend1.reply(stats1)
      backend2.expectMsg(GetDSLinkStats)
      val stats2 = DSLinkNodeStats(backend1.ref.path.address, 3, 1, 0, 1, 2, 0)
      backend2.reply(stats2)
      expectMsg(DSLinkStats(Map(backend1.ref.path.address -> stats1, backend2.ref.path.address -> stats2)))
    }
    "search dslinks" in {
      frontend ! FindDSLinks("abc.*", 3, 2)
      backend1.expectMsg(FindDSLinks("abc.*", 3, 2))
      backend1.reply(List("abc1", "abc3", "abc5", "abc7", "abc9"))
      backend2.expectMsg(FindDSLinks("abc.*", 3, 2))
      backend2.reply(List("abc2", "abc4", "abc6"))
      expectMsg(List("abc3", "abc4", "abc5"))
    }
    "remove disconnected dslinks" in {
      frontend ! RemoveDisconnectedDSLinks
      backend1.expectMsg(RemoveDisconnectedDSLinks)
      backend2.expectMsg(RemoveDisconnectedDSLinks)
    }
  }
}