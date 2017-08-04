package models.akka

import com.typesafe.config.ConfigFactory

import akka.actor.{ ActorSystem, actorRef2Scala }
import akka.pattern.ask
import akka.testkit.{ TestActors, TestKit, TestProbe }
import akka.util.Timeout
import models.Settings
import models.akka.cluster.ClusteredDSLinkManager
import models.akka.local.LocalDSLinkManager

/**
 * BackendActor test suite.
 */
class BackendActorSpec extends AbstractActorSpec {
  import BackendActor._
  import Messages._

  implicit val timeout = Timeout(Settings.QueryTimeout)

  val backendSystem = {
    val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=2551").withFallback(ConfigFactory.load("backend.conf"))
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
    TestKit.shutdownActorSystem(backendSystem)
  }

  val localMgr = new LocalDSLinkManager()(system)
  val clusterMgr = new ClusteredDSLinkManager(false)(backendSystem)

  "Local BackendActor" should {
    val fe = TestProbe()(system)
    val frontend = system.actorOf(TestActors.forwardActorProps(fe.ref), "frontend")
    val backend = system.actorOf(BackendActor.props(localMgr), "backend")
    "register with frontend" in {
      fe.expectMsg(RegisterBackend)
    }
    "register dslinks" in {
      backend ! RegisterDSLink("abc", DSLinkMode.Requester, false)
      backend ! RegisterDSLink("xyz", DSLinkMode.Responder, false)
      whenReady(backend ? GetDSLinkStats) {
        _ mustBe DSLinkNodeStats(backend.path.address, 0, 1, 0, 1, 0, 0)
      }
    }
    "handle change dslink state" in {
      backend ! DSLinkStateChanged("xyz", DSLinkMode.Responder, true)
      whenReady(backend ? GetDSLinkStats) {
        _ mustBe DSLinkNodeStats(backend.path.address, 0, 1, 1, 0, 0, 0)
      }
      backend ! DSLinkStateChanged("xyz", DSLinkMode.Responder, false)
      whenReady(backend ? GetDSLinkStats) {
        _ mustBe DSLinkNodeStats(backend.path.address, 0, 1, 0, 1, 0, 0)
      }
    }
    "search dslinks" in {
      whenReady(backend ? FindDSLinks("a.*", 100, 0)) {
        _ mustBe List("abc")
      }
    }
    "unregister dslinks" in {
      backend ! UnregisterDSLink("abc")
      whenReady(backend ? GetDSLinkStats) {
        _ mustBe DSLinkNodeStats(backend.path.address, 0, 0, 0, 1, 0, 0)
      }
    }
  }

  "Cluster BackendActor" should {
    val fe = TestProbe()(frontendSystem)
    val backend = backendSystem.actorOf(BackendActor.props(clusterMgr), "backend")
    val frontend = frontendSystem.actorOf(TestActors.forwardActorProps(fe.ref), "frontend")
    "register with frontend" in {
      fe.expectMsg(RegisterBackend)
    }
    "register dslinks" in {
      backend ! RegisterDSLink("abc", DSLinkMode.Requester, false)
      backend ! RegisterDSLink("xyz", DSLinkMode.Responder, false)
      whenReady(backend ? GetDSLinkStats) {
        _ mustBe DSLinkNodeStats(backend.path.address, 0, 1, 0, 1, 0, 0)
      }
    }
    "handle change dslink state" in {
      backend ! DSLinkStateChanged("xyz", DSLinkMode.Responder, true)
      whenReady(backend ? GetDSLinkStats) {
        _ mustBe DSLinkNodeStats(backend.path.address, 0, 1, 1, 0, 0, 0)
      }
      backend ! DSLinkStateChanged("xyz", DSLinkMode.Responder, false)
      whenReady(backend ? GetDSLinkStats) {
        _ mustBe DSLinkNodeStats(backend.path.address, 0, 1, 0, 1, 0, 0)
      }
    }
    "search dslinks" in {
      whenReady(backend ? FindDSLinks("x.*", 100, 0)) {
        _ mustBe List("xyz")
      }
    }
    "unregister dslinks" in {
      backend ! UnregisterDSLink("abc")
      whenReady(backend ? GetDSLinkStats) {
        _ mustBe DSLinkNodeStats(backend.path.address, 0, 0, 0, 1, 0, 0)
      }
    }
  }
}