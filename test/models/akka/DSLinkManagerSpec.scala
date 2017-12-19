package models.akka

import org.scalatest.Inside

import com.typesafe.config.ConfigFactory

import akka.actor.{ Actor, ActorSystem, PoisonPill, Props }
import akka.testkit.{ TestActors, TestKit, TestProbe }
import akka.util.Timeout
import models.Settings
import models.akka.cluster.ClusteredDSLinkManager
import models.akka.local.LocalDSLinkManager
import models.akka.local.DownstreamActor.GetOrCreateDSLink

/**
 * DSLinkManager test suite.
 */
class DSLinkManagerSpec extends AbstractActorSpec with Inside {
  import BackendActor._
  import Messages._

  implicit val timeout = Timeout(Settings.QueryTimeout)

  val backendSystem = {
    val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=2551").withFallback(ConfigFactory.load("backend.conf"))
    val systemName = config.getString("play.akka.actor-system")
    ActorSystem(systemName, config.resolve)
  }

  override def afterAll = {
    super.afterAll
    TestKit.shutdownActorSystem(backendSystem)
  }

  "LocalDSLinkManager" should {
    val localMgr: DSLinkManager = new LocalDSLinkManager()(system)
    val abcProbe = TestProbe()(system)
    val downstreamActor = system.actorOf(Props(new Actor {
      val abc = context.actorOf(TestActors.forwardActorProps(abcProbe.ref), "abc")
      val xyz = context.actorOf(TestActors.echoActorProps, "xyz")
      def receive = { case GetOrCreateDSLink("abc") => sender ! abc }
    }), "downstream")

    "send a message to a dslink" in {
      localMgr.tellDSLink("abc", "MSG1")
      abcProbe.expectMsg("MSG1")
    }
    "send request-reply to a dslink" in {
      whenReady(localMgr.askDSLink[String]("xyz", "MSG2")) { _ mustBe "MSG2" }
    }
    "connect dslink to endpoint" in {
      val ci = ConnectionInfo("", "abc", true, false)
      localMgr.connectEndpoint("abc", testActor, ci)
      abcProbe.expectMsg(ConnectEndpoint(testActor, ci))
    }
    "disconnect dslink to endpoint" in {
      localMgr.disconnectEndpoint("abc", false)
      abcProbe.expectMsg(DisconnectEndpoint(false))
    }
    "get dslink info" in {
      localMgr.getDSLinkInfo("abc")
      abcProbe.expectMsg(GetLinkInfo)
    }
    "create dslink comm proxy" in {
      localMgr.getCommProxy("abc") mustBe a[ActorRefProxy]
    }
  }

  "ClusteredDSLinkManager" should {
    val clusterMgr: DSLinkManager = new ClusteredDSLinkManager(false, nullDaos)(backendSystem)
    val backendProbe = TestProbe()(backendSystem)
    val backend = backendSystem.actorOf(TestActors.forwardActorProps(backendProbe.ref), "backend")

    "send a message to a dslink" in {
      clusterMgr.tellDSLink("abc", ConnectEndpoint(testActor, ConnectionInfo("", "abc", true, false)))
      backendProbe.expectMsg(RegisterDSLink("abc", DSLinkMode.Requester, true))
      clusterMgr.tellDSLink("abc", PoisonPill)
      backendProbe.expectMsg(UnregisterDSLink("abc"))
    }
    "send request-reply to a dslink" in {
      val ci = ConnectionInfo("", "xyz", true, false)
      whenReady(clusterMgr.askDSLink[LinkInfo]("xyz", GetLinkInfo)) { _ mustBe LinkInfo(ci, false, None, None) }
    }
    "connect dslink to endpoint" in {
      val ci = ConnectionInfo("", "abc", true, false)
      clusterMgr.connectEndpoint("abc", testActor, ci)
      backendProbe.expectMsg(RegisterDSLink("abc", DSLinkMode.Requester, true))
    }
    "disconnect dslink to endpoint" in {
      clusterMgr.disconnectEndpoint("abc", false)
      backendProbe.expectMsg(DSLinkStateChanged("abc", DSLinkMode.Requester, false))
    }
    "get dslink info" in {
      val ci = ConnectionInfo("", "abc", true, false)
      whenReady(clusterMgr.getDSLinkInfo("abc"))(inside(_) {
        case LinkInfo(connInfo, false, Some(_), Some(_)) => connInfo mustBe ci
      })
    }
    "create dslink comm proxy" in {
      clusterMgr.getCommProxy("abc") mustBe a[ShardedActorProxy]
    }
  }
}