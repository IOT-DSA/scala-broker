package models.bench

import scala.concurrent.duration.DurationInt

import akka.util.Timeout
import akka.pattern.ask
import models.akka.AbstractActorSpec
import models.akka.Messages.GetDSLinkNames
import models.akka.local.{ LocalDSLinkFolderActor, LocalDSLinkManager }

/**
 * BenchmarkActor test suite.
 */
class BenchmarkActorSpec extends AbstractActorSpec {
  import BenchmarkActor._
  import models.Settings._

  implicit val timeout = Timeout(3 seconds)
  val dsId = "link" + "?" * 44

  val dslinkMgr = new LocalDSLinkManager(nullDaos)
  val downstream = system.actorOf(LocalDSLinkFolderActor.props(
    Paths.Downstream, dslinkMgr.dnlinkProps, "downstream" -> true), Nodes.Downstream)

  val bench = system.actorOf(BenchmarkActor.props(nullDaos))

  "CreateResponders" should {
    "create a list of responders" in {
      bench ! CreateResponders(1 to 5, 10, 5 seconds, None, false)
      val msg = expectMsgType[RespondersReady]
      msg.responders.size mustBe 5
    }
  }

  "CreateRequesters" should {
    "create a list of requesters" in {
      bench ! CreateAndStartRequesters(false, 1 to 20, 5, 10, 100, 1000, 5 seconds, None, false)
      val msg = expectMsgType[RequestersStarted]
      msg.requesters.size mustBe 20
      msg.requesters.values foreach { tgt =>
        tgt.rspIndex must (be >= 1 and be <= 5)
        tgt.nodeIndex must (be >= 1 and be <= 10)
      }
    }
  }

  "StopApp" should {
    "kill all requesters and responders" in {
      bench ! StopAll
      Thread.sleep(1000)
      whenReady((downstream ? GetDSLinkNames).mapTo[Iterable[String]]) { _ mustBe empty }
    }
  }
}