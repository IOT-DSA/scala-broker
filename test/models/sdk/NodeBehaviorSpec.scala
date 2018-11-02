package models.sdk

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import akka.util.Timeout
import models.akka.AbstractActorSpec
import models.api.DSAValueType
import models.api.DSAValueType._
import models.rpc.DSAValue._
import models.sdk.NodeBehavior._
import models.sdk.NodeCommand._
import models.sdk.NodeEvent._

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * NodeBehavior test suite.
  */
class NodeBehaviorSpec extends AbstractActorSpec {

  import ActionContext._

  implicit val timeout: Timeout = 5 seconds
  implicit val scheduler = system.scheduler
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  "NodeBehavior" should {
    "work as a typed ActorSystem" in {
      val actorSystem = createActorSystem("dsa")
      actorSystem.name mustBe "dsa"
      checkStatus(actorSystem, NodeStatus("user"))
    }
    "work as a child of typed actor" in {
      val as = createActorSystem("dsa")
      as.systemActorOf(Behaviors.setup[NodeCommand] { ctx =>
        val child = ctx.spawn(node("child", Some(ctx.self)), "child")
        checkStatus(child, NodeStatus("child", Some(ctx.self)))
        node("root", None)
      }, "root")
    }
    "work as a child of untyped actor system" in {
      val root = system.spawn(node("root2", None), "root2")
      checkStatus(root, NodeStatus("root2"))
    }
    "work as a child of untyped actor" in {
      system.actorOf(akka.actor.Props(new akka.actor.Actor {
        untyped =>
        val child = context.spawn(node("child", Some(self)), "child")
        checkStatus(child, NodeStatus("child", Some(untyped.self)))

        def receive = {
          case _ =>
        }
      }), "root3")
    }
  }

  val root = system.spawn(node("root", None), "root")

  "NodeBehavior" should {
    "handle basic management commands" in {
      root ! SetValue(Some(555))
      checkStatus(root, NodeStatus(name = "root", value = Some(555)))
    }
    "handle attribute commands" in {
      root ! SetAttributes(Map("a" -> 1, "@b" -> 2))
      checkStatus(root, NodeStatus(name = "root", value = Some(555), attributes = Map("@a" -> 1, "@b" -> 2)))
      root ! PutAttribute("x", true)
      checkStatus(root, NodeStatus(name = "root", value = Some(555), attributes = Map("@a" -> 1, "@b" -> 2, "@x" -> true)))
      root ! RemoveAttribute("b")
      checkStatus(root, NodeStatus(name = "root", value = Some(555), attributes = Map("@a" -> 1, "@x" -> true)))
      root ! RemoveAttribute("@x")
      checkStatus(root, NodeStatus(name = "root", value = Some(555), attributes = Map("@a" -> 1)))
      root ! ClearAttributes
      checkStatus(root, NodeStatus(name = "root", value = Some(555), attributes = Map.empty))
    }
    "handle special config commands" in {
      root ! SetDisplayName("Root")
      checkStatus(root, NodeStatus(name = "root", value = Some(555), configs = Map(DisplayCfg -> "Root")))
      root ! SetValueType(DSANumber)
      checkStatus(root, NodeStatus(name = "root", value = Some(555), configs = Map(DisplayCfg -> "Root",
        ValueTypeCfg -> DSANumber)))
      root ! SetProfile("something")
      checkStatus(root, NodeStatus(name = "root", value = Some(555), configs = Map(DisplayCfg -> "Root",
        ValueTypeCfg -> DSANumber, ProfileCfg -> "something")))
    }
    "handle general config commands" in {
      root ! SetConfigs(Map("a" -> 1, "$b" -> 2))
      checkStatus(root, NodeStatus(name = "root", value = Some(555), configs = Map("$a" -> 1, "$b" -> 2)))
      root ! PutConfig("x", true)
      checkStatus(root, NodeStatus(name = "root", value = Some(555), configs = Map("$a" -> 1, "$b" -> 2, "$x" -> true)))
      root ! RemoveConfig("b")
      checkStatus(root, NodeStatus(name = "root", value = Some(555), configs = Map("$a" -> 1, "$x" -> true)))
      root ! RemoveConfig("$x")
      checkStatus(root, NodeStatus(name = "root", value = Some(555), configs = Map("$a" -> 1)))
      root ! ClearConfigs
      checkStatus(root, NodeStatus(name = "root", value = Some(555)))
    }
    "handle AddChild command" in {
      val fChildA = root ? (AddChild("aaa", _)): Future[NodeRef]
      whenReady(fChildA) { child =>
        child ! SetDisplayName("Aaa")
        child ! SetValue(Some(true))
        checkStatus(child, NodeStatus(name = "aaa", parent = Some(root), value = Some(true),
          configs = Map(DisplayCfg -> "Aaa")))
      }
      val fChildB = root ? (AddChild("bbb", _)): Future[NodeRef]
      whenReady(fChildB) { child =>
        child ! SetDisplayName("Bbb")
        child ! SetValue(Some(2))
        child ! SetAttributes(Map("x" -> "y"))
        checkStatus(child, NodeStatus(name = "bbb", parent = Some(root), value = Some(2), attributes = Map("@x" -> "y"),
          configs = Map(DisplayCfg -> "Bbb")))
      }
    }
    "handle GetChildren command" in {
      whenReady((root ? (GetChildren)): Future[NodeRefs]) { children =>
        children.map(_.path.name).toSet mustBe Set("aaa", "bbb")
      }
    }
    "handle GetChild command" in {
      val fChild: Future[Option[NodeRef]] = root ? (GetChild("aaa", _))
      whenReady(fChild) { child =>
        checkStatus(child.value, NodeStatus(name = "aaa", parent = Some(root), value = Some(true),
          configs = Map(DisplayCfg -> "Aaa")))
      }
    }
    "handle RemoveChild command" in {
      root ! RemoveChild("aaa", null)
      Thread.sleep(500)
      whenReady((root ? (GetChildren)): Future[NodeRefs]) { children =>
        children.map(_.path.name) mustBe List("bbb")
      }
    }
    "handle SetAction command" in {
      val fChild: Future[Option[NodeRef]] = root ? (GetChild("bbb", _))
      val action = NodeAction(ctx => {
        val src = Source(Stream.from(1)).map(i => i: DSAVal).delay(100 millisecond)
        val x = ctx.as[Int]("x")
        for {
          parent <- (ctx.actionNode ? (GetStatus)).map(_.parent.value)
          status <- parent ? (GetStatus)
          value = status.value.getOrElse(0: DSAVal)
        } yield {
          parent ! SetValue(Some((value: Int) + 1))
          ActionResult(x * (value: Int), Some(src))
        }
      }, Param("x", DSAValueType.DSANumber))
      fChild foreach { child =>
        child.value ! SetAction(action)
        checkStatus(child.value, NodeStatus(name = "bbb", parent = Some(root), value = Some(2), invokable = true,
          attributes = Map("@x" -> "y"), configs = Map(DisplayCfg -> "Bbb")))
      }
    }
    "handle valid Invoke command" in {
      val fResult = for {
        child <- (root ? (GetChild("bbb", _))): Future[Option[NodeRef]]
        result <- (child.value ? (Invoke(Map("x" -> 5), _))): Future[ActionResult]
        stream <- result.stream.value.take(3).runWith(Sink.seq)
        status <- root ? (GetStatus)
      } yield (result.value, stream, status)
      whenReady(fResult) { res =>
        res._1 mustBe NumericValue(5 * 555)
        res._2 mustBe Seq(1: DSAVal, 2: DSAVal, 3: DSAVal)
        res._3.value mustBe Some(556: DSAVal)
      }
    }
    "handle RemoveChildren command" in {
      root ! RemoveChildren(null)
      Thread.sleep(500)
      whenReady((root ? (GetChildren)): Future[NodeRefs]) { children =>
        children.map(_.path.name) mustBe empty
      }
    }
    "handle Stop command and recover from failure" in {
      root ! SetAttributes(Map("a" -> 1, "b" -> true))
      val fChild1 = root ? (AddChild("a1", _)): Future[NodeRef]
      fChild1 foreach { child =>
        child ! SetDisplayName("A1")
        child ! SetValue(Some(3))
        child ! SetAttributes(Map("x" -> true))
      }
      root ? (AddChild("a2", _)): Future[NodeRef]
      Thread.sleep(1000)
      watch(root.toUntyped)
      root ! Stop
      expectTerminated(root.toUntyped)

      val root2 = system.spawn(node("root", None), "root")
      checkStatus(root2, NodeStatus(name = "root", value = Some(556), attributes = Map("@a" -> 1, "@b" -> true)))
      whenReady(root2 ? (GetChildren)) { children =>
        children.map(_.path.name).toSet mustBe Set("a1", "a2")
      }

      val fChild: Future[Option[NodeRef]] = root2 ? (GetChild("a1", _))
      whenReady(fChild) { child =>
        checkStatus(child.value, NodeStatus(name = "a1", parent = Some(root2), value = Some(3),
          attributes = Map("@x" -> true), configs = Map(DisplayCfg -> "A1")))
      }
    }
  }

  val link = system.spawn(node("link", None), "link")

  "NodeBehavior" should {
    "notify about value changes" in {
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      link ! AddValueListener(probe1.ref)
      link ! AddValueListener(probe2.ref)
      link ! SetValue(Some("abc"))
      probe1.expectMsg(ValueChanged(Some("abc")))
      probe2.expectMsg(ValueChanged(Some("abc")))
      link ! RemoveValueListener(probe1.ref)
      link ! SetValue(None)
      probe1.expectNoMessage()
      probe2.expectMsg(ValueChanged(None))
      link ! RemoveAllValueListeners
      link ! SetValue(Some("xyz"))
      probe1.expectNoMessage()
      probe2.expectNoMessage()
    }
    "notify about attribute changes" in {
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      link ! AddAttributeListener(probe1.ref)
      link ! AddAttributeListener(probe2.ref)
      link ! SetAttributes(Map("a" -> 1, "b" -> true))
      probe1.expectMsg(AttributesChanged(Map("@a" -> 1, "@b" -> true)))
      probe2.expectMsg(AttributesChanged(Map("@a" -> 1, "@b" -> true)))
      link ! PutAttribute("@c", "xyz")
      probe1.expectMsg(AttributeAdded("@c", "xyz"))
      probe2.expectMsg(AttributeAdded("@c", "xyz"))
      link ! RemoveAttributeListener(probe1.ref)
      link ! RemoveAttribute("b")
      probe1.expectNoMessage()
      probe2.expectMsg(AttributeRemoved("@b"))
      link ! ClearAttributes
      probe1.expectNoMessage()
      probe2.expectMsg(AttributesChanged(Map.empty))
      link ! RemoveAllAttributeListeners
      link ! PutAttribute("d", 5)
      probe1.expectNoMessage()
      probe2.expectNoMessage()
    }
    "notify about config changes" in {
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      link ! AddConfigListener(probe1.ref)
      link ! AddConfigListener(probe2.ref)
      link ! SetConfigs(Map("a" -> 1, "b" -> true))
      probe1.expectMsg(ConfigsChanged(Map("$a" -> 1, "$b" -> true)))
      probe2.expectMsg(ConfigsChanged(Map("$a" -> 1, "$b" -> true)))
      link ! PutConfig("$c", "xyz")
      probe1.expectMsg(ConfigAdded("$c", "xyz"))
      probe2.expectMsg(ConfigAdded("$c", "xyz"))
      link ! RemoveConfigListener(probe1.ref)
      link ! RemoveConfig("b")
      probe1.expectNoMessage()
      probe2.expectMsg(ConfigRemoved("$b"))
      link ! ClearConfigs
      probe1.expectNoMessage()
      probe2.expectMsg(ConfigsChanged(Map.empty))
      link ! SetDisplayName("Link")
      probe1.expectNoMessage()
      probe2.expectMsg(ConfigAdded("$name", "Link"))
      link ! SetValueType(DSAArray)
      probe1.expectNoMessage()
      probe2.expectMsg(ConfigAdded("$type", DSAArray))
      link ! SetProfile("device")
      probe1.expectNoMessage()
      probe2.expectMsg(ConfigAdded("$is", "device"))
      link ! RemoveAllConfigListeners
      link ! PutConfig("$x", 3)
      probe1.expectNoMessage()
      probe2.expectNoMessage()
    }
    "notify about child changes" in {
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      link ! AddChildListener(probe1.ref)
      link ! AddChildListener(probe2.ref)
      link ! AddChild("aaa", null)
      link ! AddChild("bbb", null)
      probe1.expectMsg(ChildAdded("aaa"))
      probe1.expectMsg(ChildAdded("bbb"))
      probe2.expectMsg(ChildAdded("aaa"))
      probe2.expectMsg(ChildAdded("bbb"))
      link ! RemoveChildListener(probe1.ref)
      link ! RemoveChild("aaa", null)
      probe1.expectNoMessage()
      probe2.expectMsg(ChildRemoved("aaa"))
      link ! RemoveChildren(null)
      probe1.expectNoMessage()
      probe2.expectMsg(ChildrenRemoved)
      link ! RemoveAllChildListeners
      link ! AddChild("ccc", null)
      probe1.expectNoMessage()
      probe2.expectNoMessage()
    }
  }

  /**
    * Compares the current node status against the expected one.
    *
    * @param node
    * @param expected
    * @return
    */
  def checkStatus(node: NodeRef, expected: NodeStatus) = whenReady(node ? (GetStatus))(_ mustBe expected)
}
