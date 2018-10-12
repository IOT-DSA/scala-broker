package models.api

import akka.actor.TypedActor
import models.akka.StandardActions.ActionDescription
import models.akka.{AbstractActorSpec, StandardActions}
import models.api.DSAValueType._
import models.rpc.DSAValue._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * StandardActions test suite.
  */
class StandardActionsSpec extends AbstractActorSpec {

  val extension = TypedActor(system)

  "Param" should {
    "implement withEditor" in {
      Param("abc", DSAString).withEditor("daterange").editor.value mustBe "daterange"
    }
    "implement writableAs" in {
      Param("abc", DSAString).writableAs("config").writable.value mustBe "config"
    }
    "implement asOutput" in {
      Param("abc", DSAString).asOutput().output mustBe true
    }
    "implement toMapValue" in {
      Param("abc", DSANumber).toMapValue mustBe obj("name" -> "abc", "type" -> DSANumber)
      Param("abc", DSANumber, Some("date")).toMapValue mustBe obj("name" -> "abc", "type" -> DSANumber,
        "editor" -> "date")
      Param("abc", DSANumber, Some("date"), Some("config")).toMapValue mustBe obj("name" -> "abc",
        "type" -> DSANumber, "editor" -> "date", "writable" -> "config")
      Param("abc", DSANumber, Some("date"), Some("config"), true).toMapValue mustBe obj("name" -> "abc",
        "type" -> DSANumber, "editor" -> "date", "writable" -> "config", "output" -> true)
    }
  }

  "bindActions" should {
    "add actions to a node" in {
      val node: DSANode = extension.typedActorOf(DSANode.props(None), "foo")
      val action = DSAAction((ctx: ActionContext) => {}, Param("name", DSAString))
      val ad = ActionDescription("abc", "ABC", action, Some("read"), Some("node"))
      val futures = Future.sequence(StandardActions.bindActions(node, ad))
      whenReady(futures) { children =>
        children.size mustBe 1
        val child = children(0)
        child.name mustBe "abc"
        child.action mustBe Some(action)
      }
      whenReady(futures.flatMap(_.head.configs)) { configs =>
        configs mustBe obj(
          "$name" -> "ABC",
          "$invokable" -> "read",
          "$is" -> "node",
          "$params" -> array(obj("name" -> "name", "type" -> "string")),
          "$columns" -> array()
        ).value
      }
    }
  }

  "AddToken" should {
    "create a token node" in {
      val node: DSANode = extension.typedActorOf(DSANode.props(None), "nodeX")
      val fAction = Future.sequence(StandardActions.bindActions(node,
        ActionDescription("add", "Add", StandardActions.AddToken))).map(_.head)
      whenReady(fAction) { action =>
        val result = action.invoke(Map("Group" -> "GroupA"))
        result mustBe a[Option[_]]
        val (tokenId, token) = result.asInstanceOf[Option[_]].value
        tokenId mustBe token.toString.take(16)
      }
    }
  }

  "bindDataRootActions" should {
    "append AddNode and AddValue actions" in {
      val node: DSANode = extension.typedActorOf(DSANode.props(None), "node1")
      whenReady(Future.sequence(StandardActions.bindDataRootActions(node))) { children =>
        children.size mustBe 2
        children(0).name mustBe "addNode"
        children(0).action mustBe Some(StandardActions.AddNode)
        children(1).name mustBe "addValue"
        children(1).action mustBe Some(StandardActions.AddValue)
      }
    }
  }

  "bindDataNodeActions" should {
    "append AddNode, AddValue, SetValue, SetAttribute, SetConfig and DeleteNode actions" in {
      val node: DSANode = extension.typedActorOf(DSANode.props(None), "node2")
      whenReady(Future.sequence(StandardActions.bindDataNodeActions(node))) { children =>
        children.size mustBe 6
        children(0).name mustBe "addNode"
        children(0).action mustBe Some(StandardActions.AddNode)
        children(1).name mustBe "addValue"
        children(1).action mustBe Some(StandardActions.AddValue)
        children(2).name mustBe "setValue"
        children(2).action mustBe Some(StandardActions.SetValue)
        children(3).name mustBe "setAttribute"
        children(3).action mustBe Some(StandardActions.SetAttribute)
        children(4).name mustBe "setConfig"
        children(4).action mustBe Some(StandardActions.SetConfig)
        children(5).name mustBe "deleteNode"
        children(5).action mustBe Some(StandardActions.DeleteNode)
      }
    }
  }

  "bindTokenGroupNodeActions" should {
    "append AddToken action" in {
      val node: DSANode = extension.typedActorOf(DSANode.props(None), "node3")
      whenReady(Future.sequence(StandardActions.bindTokenGroupNodeActions(node))) { children =>
        children.size mustBe 1
        children(0).name mustBe "add"
        children(0).action mustBe Some(StandardActions.AddToken)
      }
    }
  }

  "bindTokenNodeActions" should {
    "append RemoveToken, TokenRemoveClients and RegenerateToken actions" in {
      val node: DSANode = extension.typedActorOf(DSANode.props(None), "node4")
      whenReady(Future.sequence(StandardActions.bindTokenNodeActions(node))) { children =>
        children.size mustBe 3
        children(0).name mustBe "remove"
        children(0).action mustBe Some(StandardActions.DeleteNode)
        children(1).name mustBe "removeAllClients"
        children(1).action mustBe Some(StandardActions.RemoveTokenClients)
        children(2).name mustBe "regenerate"
        children(2).action mustBe Some(StandardActions.RegenerateToken)
      }
    }
  }

  "bindRolesNodeActions" should {
    "append AddRole action" in {
      val node: DSANode = extension.typedActorOf(DSANode.props(None), "node5")
      whenReady(Future.sequence(StandardActions.bindRolesNodeActions(node))) { children =>
        children.size mustBe 1
        children(0).name mustBe "addRole"
        children(0).action mustBe Some(StandardActions.AddRoleNode)
      }
    }
  }

  "bindRoleNodeActions" should {
    "append AddRule and RemoveRole actions" in {
      val node: DSANode = extension.typedActorOf(DSANode.props(None), "node6")
      whenReady(Future.sequence(StandardActions.bindRoleNodeActions(node))) { children =>
        children.size mustBe 2
        children(0).name mustBe "addRule"
        children(0).action mustBe Some(StandardActions.AddRuleNode)
        children(1).name mustBe "removeRole"
        children(1).action mustBe Some(StandardActions.DeleteNode)
      }
    }
  }

  "bindRuleNodeActions" should {
    "append RemoveRule action" in {
      val node: DSANode = extension.typedActorOf(DSANode.props(None), "node7")
      whenReady(Future.sequence(StandardActions.bindRuleNodeActions(node))) { children =>
        children.size mustBe 1
        children(0).name mustBe "removeRule"
        children(0).action mustBe Some(StandardActions.DeleteNode)
      }
    }
  }
}
