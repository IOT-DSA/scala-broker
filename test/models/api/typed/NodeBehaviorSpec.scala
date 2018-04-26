package models.api.typed

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import MgmtCommand._
import NodeBehavior.{ createActorSystem, node }
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import models.akka.AbstractActorSpec
import models.rpc.DSAValue.{ BooleanValue, DSAVal, StringValue, longToNumericValue }

/**
 * NodeBehavior test suite.
 */
class NodeBehaviorSpec extends AbstractActorSpec {

  val rootState = InitState(None, 0, Map.empty)

  "NodeBehavior" should {
    "work as a typed ActorSystem" in {
      val root = createActorSystem("dsa", rootState)
      checkState(root, DSANodeState(None, "user", None, 0, Map.empty))
    }
    "work as a child of typed actor" in {
      val as = createActorSystem("dsa", rootState)
      val root = as.systemActorOf(Behaviors.setup[NodeCommand] { ctx =>
        val child = ctx.spawn(node(rootState.toInternal(Some(ctx.self))), "child")
        checkState(child, DSANodeState(Some(ctx.self), "child", None, 0, Map.empty))
        node(rootState.toInternal(None))
      }, "root")
    }
    "work as a child of untyped actor system" in {
      val root = system.spawn(node(rootState.toInternal(None)), "root2")
      checkState(root, DSANodeState(None, "root2", None, 0, Map.empty))
    }
    "work as a child of untyped actor" in {
      val root = system.actorOf(akka.actor.Props(new akka.actor.Actor { untyped =>
        val child = context.spawn(node(rootState.toInternal(Some(self))), "child")
        checkState(child, DSANodeState(Some(untyped.self), "child", None, 0, Map.empty))
        def receive = { case _ => }
      }), "root3")
    }
  }

  val root = system.spawn(NodeBehavior.node(rootState.toInternal(None)), "root")

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler = system.scheduler
  import system.dispatcher

  "NodeBehavior" should {
    "handle basic management commands" in {
      root ! SetDisplayName(Some("Root"))
      root ! SetValue(555)
      checkState(root, DSANodeState(None, "root", Some("Root"), 555, Map.empty))
    }
    "handle attribute commands" in {
      root ! SetAttributes(Map("a" -> 1, "b" -> 2))
      checkState(root, DSANodeState(None, "root", Some("Root"), 555, Map("a" -> 1, "b" -> 2)))
      root ! PutAttribute("x", true)
      checkState(root, DSANodeState(None, "root", Some("Root"), 555, Map("a" -> 1, "b" -> 2, "x" -> true)))
      root ! RemoveAttribute("b")
      checkState(root, DSANodeState(None, "root", Some("Root"), 555, Map("a" -> 1, "x" -> true)))
      root ! ClearAttributes
      checkState(root, DSANodeState(None, "root", Some("Root"), 555, Map.empty))
    }
    "handle AddChild command" in {
      val fStateA = for {
        child <- root ? (AddChild("aaa", InitState(Some("Aaa"), 3), _)): Future[NodeRef]
        state <- child ? (GetState)
      } yield state
      val fStateB = for {
        child <- root ? (AddChild("bbb", InitState(Some("Bbb"), true, Map("x" -> "y")), _)): Future[NodeRef]
        state <- child ? (GetState)
      } yield state
      whenReady(fStateA) { state =>
        state.parent.value.path.name mustBe "root"
        state.name mustBe "aaa"
        state.displayName.value mustBe "Aaa"
        state.value mustBe (3: DSAVal)
        state.attributes mustBe empty
      }
      whenReady(fStateB) { state =>
        state.parent.value.path.name mustBe "root"
        state.name mustBe "bbb"
        state.displayName.value mustBe "Bbb"
        state.value mustBe (true: DSAVal)
        state.attributes mustBe Map("x" -> ("y": DSAVal))
      }
    }
    "handle GetChildren command" in {
      whenReady((root ? (GetChildren)): Future[NodeRefs]) { children =>
        children.map(_.path.name).toSet mustBe Set("aaa", "bbb")
      }
    }
    "handle RemoveChild command" in {
      root ! RemoveChild("aaa")
      Thread.sleep(500)
      whenReady((root ? (GetChildren)): Future[NodeRefs]) { children =>
        children.map(_.path.name) mustBe List("bbb")
      }
    }
    "handle RemoveChildren command" in {
      root ! RemoveChildren
      Thread.sleep(500)
      whenReady((root ? (GetChildren)): Future[NodeRefs]) { children =>
        children.map(_.path.name) mustBe empty
      }
    }
    "handle Stop command" in {
      watch(root.toUntyped)
      root ! Stop
      expectTerminated(root.toUntyped)
    }
  }

  private def checkState(node: NodeRef, expected: DSANodeState) = whenReady(node ? (GetState))(_ mustBe expected)
}