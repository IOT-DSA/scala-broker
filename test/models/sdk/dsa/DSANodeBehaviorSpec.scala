package models.sdk.dsa

import java.util.concurrent.CountDownLatch

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.stream.ActorMaterializer
import akka.testkit.TestProbe
import akka.util.Timeout
import models.akka.AbstractActorSpec
import models.rpc.DSAValue._
import models.rpc.{DSAResponse, ListRequest, ResponseMessage, SetRequest, StreamState}
import models.sdk._
import models.sdk.dsa.DSANodeBehavior._
import models.sdk.node.NodeCommand._
import models.sdk.node.{NodeCommand, NodeStatus}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * DSANodeBehavior test suite.
  */
class DSANodeBehaviorSpec extends AbstractActorSpec {

  import ChangeMode._
  import DSANodeCommand._

  implicit val timeout: Timeout = 5 seconds
  implicit val scheduler = system.scheduler
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  val latch = new CountDownLatch(1)

  // create DSA "link" node under /user/downstream
  val downstream = system.actorOf(akka.actor.Props(new akka.actor.Actor {
    context.spawn(dsaNode(), "link")
    latch.countDown()

    override def receive: Receive = {
      case _ =>
    }
  }), "downstream")
  latch.await()

  val link: ActorRef[DSANodeCommand] = {
    val untyped = Await.result(system.actorSelection("/user/downstream/link").resolveOne, Duration.Inf)
    untyped
  }

  "DSANodeBehavior with GetNodeAPI" should {
    "return the bound node ref" in {
      val fStatus = for {
        root <- link ? GetNodeAPI
        status <- root ? GetStatus
      } yield status
      whenReady(fStatus) {
        _ mustBe NodeStatus(DSANodeBehavior.RootNode)
      }
    }
  }

  val root: ActorRef[NodeCommand] = {
    val untyped = Await.result(system.actorSelection("/user/downstream/link/root").resolveOne, Duration.Inf)
    untyped
  }

  val child = Await.result((root ? (AddChild("childA", _))): Future[NodeRef], Duration.Inf)

  val broker1 = TestProbe()

  "DSANodeBehavior with SET" should {
    "handle changing root value" in {
      link ! ProcessRequest(SetRequest(101, "/", 111), broker1.ref)
      broker1.expectMsg(ResponseMessage(0, None, DSAResponse(101, Some(StreamState.Closed)) :: Nil))
      whenReady(root ? GetStatus) { status =>
        status.value mustBe Some(111: DSAVal)
      }
    }
    "handle changing child value" in {
      link ! ProcessRequest(SetRequest(104, "/childA", 222), broker1.ref)
      broker1.expectMsg(ResponseMessage(0, None, DSAResponse(104, Some(StreamState.Closed)) :: Nil))
      whenReady(child ? GetStatus) { status =>
        status.value mustBe Some(222: DSAVal)
      }
    }
    "handle changing root attribute" in {
      link ! ProcessRequest(SetRequest(102, "/@aaa", true), broker1.ref)
      broker1.expectMsg(ResponseMessage(0, None, DSAResponse(102, Some(StreamState.Closed)) :: Nil))
      whenReady(root ? GetStatus) { status =>
        status.attributes.get("@aaa") mustBe Some(true: DSAVal)
      }
    }
    "handle changing child attribute" in {
      link ! ProcessRequest(SetRequest(105, "/childA/@bbb", true), broker1.ref)
      broker1.expectMsg(ResponseMessage(0, None, DSAResponse(105, Some(StreamState.Closed)) :: Nil))
      whenReady(child ? GetStatus) { status =>
        status.attributes.get("@bbb") mustBe Some(true: DSAVal)
      }
    }
    "handle changing root config" in {
      link ! ProcessRequest(SetRequest(103, "/$ccc", "abc"), broker1.ref)
      broker1.expectMsg(ResponseMessage(0, None, DSAResponse(103, Some(StreamState.Closed)) :: Nil))
      whenReady(root ? GetStatus) { status =>
        status.configs.get("$ccc") mustBe Some("abc": DSAVal)
      }
    }
    "handle changing child config" in {
      link ! ProcessRequest(SetRequest(106, "/childA/$ddd", "abc"), broker1.ref)
      broker1.expectMsg(ResponseMessage(0, None, DSAResponse(106, Some(StreamState.Closed)) :: Nil))
      whenReady(child ? GetStatus) { status =>
        status.configs.get("$ddd") mustBe Some("abc": DSAVal)
      }
    }
  }

  "DSANodeBehavior with LIST" should {
    "list node attributes, configs, and children" in {
      link ! ProcessRequest(ListRequest(201, "/"), broker1.ref)
      val updates = Seq(array("$ccc", "abc"), array("@aaa", true),
        array("childA", obj("$is" -> "node", "$type" -> "dynamic", "$ddd" -> "abc")))
      broker1.expectMsg(response(201, updates: _*))
      val broker2 = TestProbe()
      link ! ProcessRequest(ListRequest(202, "/"), broker2.ref)
      broker2.expectMsg(response(202, updates: _*))
    }
    "trigger updates on attribute changes" in {
      root ! PutAttribute("john", 111)
      broker1.expectMsg(response(201, array("@john", 111)))
      val broker3 = TestProbe()
      link ! ProcessRequest(ListRequest(203, "/"), broker3.ref)
      broker3.expectMsg(response(203, array("$ccc", "abc"), array("@aaa", true),
        array("@john", 111),
        array("childA", obj("$is" -> "node", "$type" -> "dynamic", "$ddd" -> "abc"))))
      root ! RemoveAttribute("@aaa")
      broker1.expectMsg(response(201, obj("name" -> "@aaa", "change" -> REMOVE)))
      broker3.expectMsg(response(203, obj("name" -> "@aaa", "change" -> REMOVE)))
    }
    "trigger updates on config changes" in {
      root ! PutConfig("jane", 111)
      broker1.expectMsg(response(201, array("$jane", 111)))
      val broker4 = TestProbe()
      link ! ProcessRequest(ListRequest(204, "/"), broker4.ref)
      broker4.expectMsg(response(204, array("$ccc", "abc"), array("$jane", 111),
        array("@john", 111),
        array("childA", obj("$is" -> "node", "$type" -> "dynamic", "$ddd" -> "abc"))))
      root ! RemoveConfig("$ccc")
      broker1.expectMsg(response(201, obj("name" -> "$ccc", "change" -> REMOVE)))
      broker4.expectMsg(response(204, obj("name" -> "$ccc", "change" -> REMOVE)))
    }
    "trigger updates on child changes" in {
      root ! AddChild("jake", null)
      broker1.expectMsg(response(201, array("jake", obj("$is" -> "node", "$type" -> "dynamic"))))
      val broker5 = TestProbe()
      link ! ProcessRequest(ListRequest(205, "/"), broker5.ref)
      broker5.expectMsg(response(205, array("$jane", 111), array("@john", 111),
        array("childA", obj("$is" -> "node", "$type" -> "dynamic", "$ddd" -> "abc")),
        array("jake", obj("$is" -> "node", "$type" -> "dynamic"))))
      root ! RemoveChild("jake", null)
      broker1.expectMsg(response(201, obj("name" -> "jake", "change" -> REMOVE)))
      broker5.expectMsg(response(205, obj("name" -> "jake", "change" -> REMOVE)))
    }
  }

  private def response(rid: Int, updates: DSAVal*) =
    ResponseMessage(0, None, DSAResponse(rid, Some(StreamState.Open), Some(updates.toList)) :: Nil)
}
