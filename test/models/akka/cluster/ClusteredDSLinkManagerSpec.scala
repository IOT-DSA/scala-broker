package models.akka.cluster

import scala.concurrent.duration.DurationInt

import org.scalatest.Inside

import com.typesafe.config.ConfigFactory

import akka.actor.ActorSystem
import akka.testkit.{ TestActors, TestKit, TestProbe }
import akka.util.Timeout
import models.{ RequestEnvelope, ResponseEnvelope, Settings }
import models.akka.{ AbstractActorSpec, DSLinkMode, RootNodeActor }
import models.akka.Messages.RegisterDSLink
import models.rpc.{ DSAResponse, ListRequest }

/**
 * ClusteredDSLinkManager test suite.
 */
class ClusteredDSLinkManagerSpec extends AbstractActorSpec with Inside {

  implicit val timeout = Timeout(3 seconds)

  // global downstream sink
  val downstreamProbe = TestProbe()(system)

  val (system1, mgr1, probe1) = createClusterArtifacts(2551, false)
  val (system2, mgr2, probe2) = createClusterArtifacts(0, false)
  val (system3, mgr3, probe3) = createClusterArtifacts(0, false)

  val managers = List(mgr1, mgr2, mgr3)
  val probes = List(probe1, probe2, probe3)

  override def afterAll = {
    super.afterAll
    TestKit.shutdownActorSystem(system3)
    TestKit.shutdownActorSystem(system2)
    TestKit.shutdownActorSystem(system1)
  }

  "getDSLinkRoutee" should {
    "return a sharded routee" in {
      val routee = mgr1.getDSLinkRoutee("aaa")
      routee mustBe a[ShardedRoutee]
      val link = routee.asInstanceOf[ShardedRoutee]
      link.region mustBe mgr1.region
      link.entityId mustBe "aaa"
    }
  }

  "dsaSend" should {
    "send a message to /downstream node" in {
      mgr1.dsaSend("/downstream", "hello")
      downstreamProbe.expectMsg("hello")
    }
    "send a message to a dslink" in {
      managers.zipWithIndex foreach {
        case (mgr, index) =>
          val name = "aaa" + index
          mgr.dsaSend(s"/downstream/$name", s"hello_$name")
          downstreamProbe.expectMsg(RegisterDSLink(name, DSLinkMode.Requester, false))
      }
    }
    "send a message to the top node" in {
      managers zip probes foreach {
        case (mgr, probe) =>
          mgr.dsaSend("/", RequestEnvelope(ListRequest(1, "/") :: Nil))(probe.ref)
          inside(probe.receiveOne(timeout.duration)) {
            case ResponseEnvelope(DSAResponse(1, Some(closed), _, _, _) :: Nil) => true
          }
      }
    }
    "send a message to a /sys node" in {
      managers zip probes foreach {
        case (mgr, probe) =>
          mgr.dsaSend("/sys", RequestEnvelope(ListRequest(2, "/sys") :: Nil))(probe.ref)
          inside(probe.receiveOne(timeout.duration)) {
            case ResponseEnvelope(DSAResponse(2, _, _, _, _) :: Nil) => true
          }
      }
    }
    "send a message to a /defs/profile node" in {
      managers zip probes foreach {
        case (mgr, probe) =>
          mgr.dsaSend("/defs/profile", RequestEnvelope(ListRequest(3, "/defs/profile") :: Nil))(probe.ref)
          inside(probe.receiveOne(timeout.duration)) {
            case ResponseEnvelope(DSAResponse(3, _, _, _, _) :: Nil) => true
          }
      }
    }
  }

  /**
   * Creates a clustered actor system and initializes other artifacts for testing.
   */
  private def createClusterArtifacts(port: Int, proxyMode: Boolean) = {
    val system = createActorSystem(port)
    val mgr = new ClusteredDSLinkManager(proxyMode, nullDaos)(system)
    system.actorOf(TestActors.forwardActorProps(downstreamProbe.ref), Settings.Nodes.Downstream)
    if (proxyMode)
      RootNodeActor.singletonProxy(system)
    else
      RootNodeActor.singletonStart(system)
    val probe = TestProbe()(system)

    (system, mgr, probe)
  }

  /**
   * Creates a clustered actor system for testing.
   */
  private def createActorSystem(port: Int) = {
    val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port")
      .withFallback(ConfigFactory.load("backend.conf"))
    val systemName = config.getString("play.akka.actor-system")
    ActorSystem(systemName, config.resolve)
  }
}