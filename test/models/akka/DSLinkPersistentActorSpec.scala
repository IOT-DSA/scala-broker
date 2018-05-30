package models.akka

import scala.concurrent.duration.DurationInt
import org.scalatest.Inside
import akka.actor.{PoisonPill, Props}
import akka.pattern.ask
import akka.routing.{ActorRefRoutee, Routee}
import akka.testkit.{TestActorRef, TestProbe}
import akka.util.Timeout

class DSLinkPersistentActorSpec extends AbstractActorSpec with Inside {
  import DSLinkPersistentActorSpec._
  import Messages._

  implicit val timeout = Timeout(5 seconds)

  val linkName = "link-abc-1"
  val ci = ConnectionInfo("link" + "?" * 44, linkName, true, false, Some("link-data-some-val"), "version777",
    List("format1", "format2"), true, "link address 1-2-3", "broker address 3-2-1")

  val downProbe = TestProbe()

  val dslink = TestActorRef[LinkActor](Props(new LinkActor(ActorRefRoutee(downProbe.ref))), linkName)

  val Seq(endpoint1, endpoint2, endpoint3) = (1 to 3) map (_ => watch(TestProbe().ref))

  "AbstractDSLinkPersist" should {

    "connect to endpoint, then kill it before restoring" in {

      downProbe.expectMsg(RegisterDSLink(linkName, DSLinkMode.Requester, false))
      // connect to generate the new state
      dslink ! ConnectEndpoint(endpoint1, ci)
      downProbe.expectMsg(DSLinkStateChanged(linkName, DSLinkMode.Requester, true))
      whenReady (dslink ? GetLinkInfo) (inside(_) {
        case LinkInfo(connInfo, _, _, _) => connInfo mustBe ci
      })

      // then kill it
      dslink ! PoisonPill
      downProbe.expectMsg(UnregisterDSLink(linkName))

      // trying to recover by linkName
      val dslinkRecovered = TestActorRef[LinkActor](Props(new LinkActor(ActorRefRoutee(downProbe.ref))), linkName)
      Thread.sleep(1000)
      whenReady (dslinkRecovered ? GetLinkInfo) (inside(_) {
        case LinkInfo(connInfo,_ ,_ , _) => connInfo mustBe ci
      })

      // TODO Create test cases to check lastConnected and lastDisconnected dates
    }
  }
}

object DSLinkPersistentActorSpec {

  /**
    * Test persist actor.
    */
  class LinkActor(registry: Routee) extends AbstractDSLinkActor(registry) {
    override def persistenceId = self.path.name
    override def receiveRecover = recoverBaseState
  }
}