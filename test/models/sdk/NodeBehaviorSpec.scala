package models.sdk

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import models.akka.AbstractActorSpec
import models.rpc.DSAValue._
import models.sdk.NodeBehavior._
import models.sdk.NodeCommand._

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * NodeBehavior test suite.
  */
class NodeBehaviorSpec extends AbstractActorSpec {

  implicit val timeout: Timeout = 5 seconds
  implicit val scheduler = system.scheduler

  import system.dispatcher

  "NodeBehavior" should {
    "work as a typed ActorSystem" in {
      val actorSystem = createActorSystem("dsa")
      actorSystem.name mustBe "dsa"
      checkStatus(actorSystem, NodeStatus(None, "user", None, None, Map.empty))
    }
    "work as a child of typed actor" in {
      val as = createActorSystem("dsa")
      as.systemActorOf(Behaviors.setup[NodeCommand] { ctx =>
        val child = ctx.spawn(node("child", Some(ctx.self)), "child")
        checkStatus(child, NodeStatus(Some(ctx.self), "child", None, None, Map.empty))
        node("root", None)
      }, "root")
    }
    "work as a child of untyped actor system" in {
      val root = system.spawn(node("root2", None), "root2")
      checkStatus(root, NodeStatus(None, "root2", None, None, Map.empty))
    }
    "work as a child of untyped actor" in {
      system.actorOf(akka.actor.Props(new akka.actor.Actor {
        untyped =>
        val child = context.spawn(node("child", Some(self)), "child")
        checkStatus(child, NodeStatus(Some(untyped.self), "child", None, None, Map.empty))

        def receive = {
          case _ =>
        }
      }), "root3")
    }
  }

  val root = system.spawn(node("root", None), "root")

  "NodeBehavior" should {
    "handle basic management commands" in {
      root ! SetDisplayName("Root")
      root ! SetValue(Some(555))
      checkStatus(root, NodeStatus(None, "root", Some("Root"), Some(555), Map.empty))
    }
    "handle attribute commands" in {
      root ! SetAttributes(Map("a" -> 1, "@b" -> 2))
      checkStatus(root, NodeStatus(None, "root", Some("Root"), Some(555), Map("@a" -> 1, "@b" -> 2)))
      root ! PutAttribute("x", true)
      checkStatus(root, NodeStatus(None, "root", Some("Root"), Some(555), Map("@a" -> 1, "@b" -> 2, "@x" -> true)))
      root ! RemoveAttribute("b")
      checkStatus(root, NodeStatus(None, "root", Some("Root"), Some(555), Map("@a" -> 1, "@x" -> true)))
      root ! RemoveAttribute("@x")
      checkStatus(root, NodeStatus(None, "root", Some("Root"), Some(555), Map("@a" -> 1)))
      root ! ClearAttributes
      checkStatus(root, NodeStatus(None, "root", Some("Root"), Some(555), Map.empty))
    }
    "handle AddChild command" in {
      val fChildA = root ? (AddChild("aaa", _)): Future[NodeRef]
      whenReady(fChildA) { child =>
        child ! SetDisplayName("Aaa")
        child ! SetValue(Some(3))
        checkStatus(child, NodeStatus(Some(root), "aaa", Some("Aaa"), Some(3), Map.empty))
      }
      val fChildB = root ? (AddChild("bbb", _)): Future[NodeRef]
      whenReady(fChildB) { child =>
        child ! SetDisplayName("Bbb")
        child ! SetValue(Some(true))
        child ! SetAttributes(Map("x" -> "y"))
        checkStatus(child, NodeStatus(Some(root), "bbb", Some("Bbb"), Some(true), Map("@x" -> "y")))
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
        checkStatus(child.value, NodeStatus(Some(root), "aaa", Some("Aaa"), Some(3), Map.empty))
      }
    }
    "handle RemoveChild command" in {
      root ! RemoveChild("aaa", null)
      Thread.sleep(500)
      whenReady((root ? (GetChildren)): Future[NodeRefs]) { children =>
        children.map(_.path.name) mustBe List("bbb")
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
      val fChild2 = root ? (AddChild("a2", _)): Future[NodeRef]
      Thread.sleep(1000)
      watch(root.toUntyped)
      root ! Stop
      expectTerminated(root.toUntyped)

      val root2 = system.spawn(node("root", None), "root")
      checkStatus(root2, NodeStatus(None, "root", Some("Root"), Some(555), Map("@a" -> 1, "@b" -> true)))
      whenReady(root2 ? (GetChildren)) { children =>
        children.map(_.path.name).toSet mustBe Set("a1", "a2")
      }

      val fChild: Future[Option[NodeRef]] = root2 ? (GetChild("a1", _))
      whenReady(fChild) { child =>
        checkStatus(child.value, NodeStatus(Some(root2), "a1", Some("A1"), Some(3), Map("@x" -> true)))
      }
    }
  }

  def checkStatus(node: NodeRef, expected: NodeStatus) = whenReady(node ? (GetStatus))(_ mustBe expected)
}
