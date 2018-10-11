package models.akka

import akka.actor.{Actor, PoisonPill, Props, actorRef2Scala}
import akka.routing.{ActorRefRoutee, Routee}
import akka.testkit.TestProbe
import models.{RequestEnvelope, ResponseEnvelope}
import models.akka.Messages._
import akka.pattern.ask
import akka.util.Timeout
import models.akka.local.LocalDSLinkManager
import org.scalatest.Inside
import scala.concurrent.duration.DurationInt
import models.rpc.{ DSAResponse, ListRequest, RequestMessage }
import models.util.DsaToAkkaCoder._

/**
 * RequesterBehavior test suite.
 */
class RequesterBehaviorSpec extends AbstractActorSpec with Inside {
  import RequesterBehaviorSpec._

  implicit val timeout = Timeout(3 seconds)

  val dslinkMgr = new LocalDSLinkManager()

  val ci = ConnectionInfo("", "", true, false)

  val abcProbe = TestProbe()
  class AbcActor extends Actor {
    def receive = { case msg => abcProbe.ref ! msg }
  }

  val downstreamProbe = TestProbe()
  class DownstreamActor extends Actor {
    val abc = context.actorOf(Props(new AbcActor), "abc def".forAkka)
    def receive = { case msg => downstreamProbe.ref ! msg }
  }
  val downstreamActor = system.actorOf(Props(new DownstreamActor), "downstream")

  val brokerProbe = TestProbe()
  class BrokerActor extends Actor {
    def receive = { case msg => brokerProbe.ref ! msg }
  }
  val brokerActor = system.actorOf(Props(new BrokerActor), "broker")

  val requester = system.actorOf(Props(new Requester(dslinkMgr, ActorRefRoutee(downstreamActor))), "requester")
  val ws = TestProbe()

  "RequesterActor" should {
    "register with downstream" in {
      downstreamProbe.expectMsg(RegisterDSLink("requester", DSLinkMode.Requester, false))
    }
    "notify downstream on connection" in {
      requester.tell(Messages.ConnectEndpoint(ws.ref, ci), ws.ref)
      downstreamProbe.expectMsg(DSLinkStateChanged("requester", DSLinkMode.Requester, true))
    }
    "route requests to broker root" in {
      val requests = List(ListRequest(1, "/"))
      requester.tell(RequestMessage(1, None, requests), ws.ref)
      brokerProbe.expectMsg(RequestEnvelope(requests))
    }
    "route requests to downstream" in {
      val requests = List(ListRequest(1, "/downstream"))
      requester.tell(RequestMessage(1, None, requests), ws.ref)
      downstreamProbe.expectMsg(RequestEnvelope(requests))
    }
    "route requests to links" in {
      val requests = List(ListRequest(1, "/downstream/abc def"))
      requester.tell(RequestMessage(1, None, requests), ws.ref)
      abcProbe.expectMsg(RequestEnvelope(requests))
    }
    "forward responses to WS" in {
      val envelope = ResponseEnvelope(List(DSAResponse(1)))
      requester.tell(envelope, testActor)
      ws.expectMsg(envelope)
    }
//    "has to be recovered" in {
//      requester ! PoisonPill
//      downstreamProbe.expectMsg(UnregisterDSLink("requester"))
//
//      Thread.sleep(500)
//      val requesterRecovered = system.actorOf(Props(new Requester(dslinkMgr, ActorRefRoutee(downstreamActor))), "requester")
//
//      whenReady(requesterRecovered ? GetLinkInfo) (inside(_) {
//        case LinkInfo(connInfo, _, _, _) => connInfo mustBe ci
//      })
//    }
  }
}

/**
 * Common definitions for [[RequesterBehaviorSpec]].
 */
object RequesterBehaviorSpec {
  /**
   * Test actor.
   */
  class Requester(val dslinkMgr: DSLinkManager, registry: Routee)
    extends AbstractDSLinkActor(registry) with RequesterBehavior {
    override def persistenceId = linkName
    override def connected = super.connected orElse requesterBehavior
    override def disconnected = super.disconnected orElse requesterDisconnected orElse toStash
    override def receiveRecover = recoverBaseState orElse requesterRecover
  }
}