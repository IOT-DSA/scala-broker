package models.akka.cluster

import scala.concurrent.duration.DurationInt

import org.scalatest.Inside

import com.typesafe.config.ConfigFactory

import akka.actor.{ ActorSystem, Props, actorRef2Scala }
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import models.{ RequestEnvelope, ResponseEnvelope }
import models.akka.{ AbstractActorSpec, DSLinkMode, IsNode, rows }
import models.rpc.{ CloseRequest, DSAResponse, ListRequest }
import akka.actor.Address

/**
 * ClusteredDSLinkFolderActor test suite.
 */
class ClusteredDSLinkFolderActorSpec extends AbstractActorSpec with Inside {
  import akka.cluster.sharding.ShardRegion._
  import models.akka.Messages._
  import models.rpc.DSAValue._
  import models.Settings._

  import system.dispatcher

  type FoundLinks = Map[Address, Iterable[String]]

  implicit val timeout = Timeout(3 seconds)
  val dsId = "link" + "?" * 44
  val dsaPath = Paths.Downstream
  val extra: (String, DSAVal) = "downstream" -> true

  val system1 = createActorSystem(2551)
  val mgr1 = new ClusteredDSLinkManager(false)(system1)
  val downstream1 = system1.actorOf(ClusteredDSLinkFolderActor.props(dsaPath, mgr1.getDownlinkRoutee, extra), Nodes.Downstream)

  val system2 = createActorSystem(0)
  val mgr2 = new ClusteredDSLinkManager(false)(system2)
  val downstream2 = system2.actorOf(ClusteredDSLinkFolderActor.props(dsaPath, mgr2.getDownlinkRoutee, extra), Nodes.Downstream)

  val system3 = createActorSystem(0)
  val mgr3 = new ClusteredDSLinkManager(false)(system3)
  val downstream3 = system3.actorOf(ClusteredDSLinkFolderActor.props(dsaPath, mgr3.getDownlinkRoutee, extra), Nodes.Downstream)

  override def afterAll = {
    super.afterAll
    TestKit.shutdownActorSystem(system3)
    TestKit.shutdownActorSystem(system2)
    TestKit.shutdownActorSystem(system1)
  }

  "GetOrCreateDSLink" should {
    "create a new dslink" in {
      whenReady(downstream1 ? GetOrCreateDSLink("aa a")) { result =>
        result mustBe a[ShardedRoutee]
        val link = result.asInstanceOf[ShardedRoutee]
        link.region mustBe mgr1.dnlinkRegion
        link.entityId mustBe "aa a"
      }
      whenReady(downstream2 ? GetOrCreateDSLink("bbb")) { result =>
        result mustBe a[ShardedRoutee]
        val link = result.asInstanceOf[ShardedRoutee]
        link.region mustBe mgr2.dnlinkRegion
        link.entityId mustBe "bbb"
      }
      whenReady(downstream3 ? GetOrCreateDSLink("ccc")) { result =>
        result mustBe a[ShardedRoutee]
        val link = result.asInstanceOf[ShardedRoutee]
        link.region mustBe mgr3.dnlinkRegion
        link.entityId mustBe "ccc"
      }
      whenReady(downstream2 ? GetOrCreateDSLink("ddd")) { result =>
        result mustBe a[ShardedRoutee]
        val link = result.asInstanceOf[ShardedRoutee]
        link.region mustBe mgr2.dnlinkRegion
        link.entityId mustBe "ddd"
      }
      whenReady(downstream1 ? GetOrCreateDSLink("eee")) { result =>
        result mustBe a[ShardedRoutee]
        val link = result.asInstanceOf[ShardedRoutee]
        link.region mustBe mgr1.dnlinkRegion
        link.entityId mustBe "eee"
      }
      Thread.sleep(3000)
      whenReady(getClusterShardingStats) { css =>
        css.regions.size mustBe 3
        css.regions.values.flatMap(_.stats.values).sum mustBe 5
      }
    }
    "return an existing DSLink actor" in {
      whenReady(downstream2 ? GetOrCreateDSLink("aa a")) { result =>
        result mustBe a[ShardedRoutee]
        val link = result.asInstanceOf[ShardedRoutee]
        link.region mustBe mgr2.dnlinkRegion
        link.entityId mustBe "aa a"
      }
      whenReady(downstream3 ? GetOrCreateDSLink("eee")) { result =>
        result mustBe a[ShardedRoutee]
        val link = result.asInstanceOf[ShardedRoutee]
        link.region mustBe mgr3.dnlinkRegion
        link.entityId mustBe "eee"
      }
      whenReady(getClusterShardingStats) { css =>
        css.regions.values.flatMap(_.stats.values).sum mustBe 5
      }
    }
  }

  "GetDSLinkNames" should {
    "return dslink names from any node" in {
      whenReady((downstream1 ? GetDSLinkNames).mapTo[Iterable[String]]) {
        _.toSet mustBe Set("aa a", "bbb", "ccc", "ddd", "eee")
      }
      whenReady((downstream2 ? GetDSLinkNames).mapTo[Iterable[String]]) {
        _.toSet mustBe Set("aa a", "bbb", "ccc", "ddd", "eee")
      }
      whenReady((downstream3 ? GetDSLinkNames).mapTo[Iterable[String]]) {
        _.toSet mustBe Set("aa a", "bbb", "ccc", "ddd", "eee")
      }
    }
    "match sharded entities" in {
      val f1 = (mgr1.dnlinkRegion ? GetShardRegionState).mapTo[CurrentShardRegionState]
      val f2 = (mgr2.dnlinkRegion ? GetShardRegionState).mapTo[CurrentShardRegionState]
      val f3 = (mgr3.dnlinkRegion ? GetShardRegionState).mapTo[CurrentShardRegionState]
      whenReady(for (s1 <- f1; s2 <- f2; s3 <- f3) yield (s1, s2, s3)) { states =>
        val shards = states._1.shards ++ states._2.shards ++ states._3.shards
        shards.flatMap(_.entityIds).toSet mustBe Set("aa a", "bbb", "ccc", "ddd", "eee")
      }
    }
  }

  "DSLinkStateChanged" should {
    "handle change dslink state" in {
      downstream3 ! DSLinkStateChanged("eee", DSLinkMode.Responder, true)
      downstream1 ! DSLinkStateChanged("bbb", DSLinkMode.Requester, false)
      downstream2 ! DSLinkStateChanged("aa a", DSLinkMode.Responder, true)
      downstream1 ! DSLinkStateChanged("ccc", DSLinkMode.Responder, false)
      downstream3 ! DSLinkStateChanged("ddd", DSLinkMode.Requester, true)
      Thread.sleep(1000)
      whenReady((downstream1 ? GetDSLinkStats).mapTo[DSLinkStats]) { stats =>
        stats.requestersOn mustBe 1
        stats.requestersOff mustBe 1
        stats.respondersOn mustBe 2
        stats.respondersOff mustBe 1
        stats.dualsOn mustBe 0
        stats.dualsOff mustBe 0
      }
      downstream1 ! DSLinkStateChanged("bbb", DSLinkMode.Requester, true)
      downstream3 ! DSLinkStateChanged("aa a", DSLinkMode.Responder, false)
      Thread.sleep(1000)
      whenReady((downstream1 ? GetDSLinkStats).mapTo[DSLinkStats]) { stats =>
        stats.requestersOn mustBe 2
        stats.requestersOff mustBe 0
        stats.respondersOn mustBe 1
        stats.respondersOff mustBe 2
        stats.dualsOn mustBe 0
        stats.dualsOff mustBe 0
      }
    }
  }

  "FindDSLinks" should {
    "search for matching dslinks" in {
      whenReady((downstream2 ? FindDSLinks("a.*", 100, 0)).mapTo[FoundLinks]) {
        _.flatMap(_._2) mustBe List("aa a")
      }
      whenReady((downstream1 ? FindDSLinks("[ad].*", 100, 0)).mapTo[FoundLinks]) {
        _.flatMap(_._2).toSet mustBe Set("aa a", "ddd")
      }
      whenReady((downstream3 ? FindDSLinks("[aed].*", 100, 0)).mapTo[FoundLinks]) {
        _.flatMap(_._2).toSet mustBe Set("aa a", "ddd", "eee")
      }
    }
  }

  "ListRequest" should {
    "return all dslinks" in {
      downstream1 ! RequestEnvelope(List(ListRequest(1, "/downstream")))
      inside(receiveOne(timeout.duration)) {
        case ResponseEnvelope(List(DSAResponse(1, Some(open), Some(list), _, _))) =>
          list.toSet mustBe rows(IsNode, "downstream" -> true,
            "aa a" -> obj(IsNode), "bbb" -> obj(IsNode),
            "ccc" -> obj(IsNode), "ddd" -> obj(IsNode),
            "eee" -> obj(IsNode)).toSet
      }
    }
    "send updates on added nodes" in {
      downstream1 ! GetOrCreateDSLink("fff")
      val Seq(routee, env) = receiveN(2)
      inside(env) {
        case ResponseEnvelope(List(DSAResponse(1, Some(open), Some(list), _, _))) =>
          list mustBe rows("fff" -> obj(IsNode))
      }
    }
    "send updates on removed nodes" in {
      downstream2 ! RemoveDSLink("ccc")
      inside(receiveOne(timeout.duration)) {
        case ResponseEnvelope(List(DSAResponse(1, Some(open), Some(list), _, _))) =>
          list mustBe List(obj("name" -> "ccc", "change" -> "remove"))
      }
    }
  }

  "CloseRequest" should {
    "return valid response" in {
      downstream1 ! RequestEnvelope(List(CloseRequest(1)))
      downstream2 ! GetOrCreateDSLink("ggg")
      expectMsgClass(classOf[ShardedRoutee])
      expectNoMessage(timeout.duration)
    }
  }

  "RemoveDisconnectedDSLinks" should {
    "remove all disconnected dslinks" in {
      downstream3 ! RemoveDisconnectedDSLinks
      Thread.sleep(1000)
      whenReady((downstream1 ? GetDSLinkStats).mapTo[DSLinkStats]) { stats =>
        println(stats)
        stats.requestersOn mustBe 2
        stats.requestersOff mustBe 0
        stats.respondersOn mustBe 1
        stats.respondersOff mustBe 0
        stats.dualsOn mustBe 0
        stats.dualsOff mustBe 0
      }
    }
  }

  private def createActorSystem(port: Int) = {
    val config = ConfigFactory.parseString(s"akka.remote.artery.canonical.port=$port")
      .withFallback(ConfigFactory.load("backend.conf"))
    val systemName = config.getString("play.akka.actor-system")
    ActorSystem(systemName, config.resolve)
  }

  private def getClusterShardingStats =
    (mgr1.dnlinkRegion ? GetClusterShardingStats(timeout.duration)).mapTo[ClusterShardingStats]
}