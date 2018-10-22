package models.api

import java.net.URLEncoder

import akka.actor.{Actor, Props, TypedActor}
import akka.routing.ActorRefRoutee
import akka.testkit.{TestActors, TestProbe}
import models.Settings.Nodes
import models.akka.Messages.{DisconnectEndpoint, GetOrCreateDSLink, RemoveDSLink}
import models.akka.StandardActions.ActionDescription
import models.akka.{AbstractActorSpec, StandardActions}
import models.rpc.DSAValue._
import models.api.DSAValueType._
import models.util.Tokens

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

  "AddNode" should {
    "append a child node to a node" in {
      val node: DSANode = createNode("nodeA")
      val fChild = for {
        _ <- bindAndInvoke(node, StandardActions.AddNode, "Name" -> "childA")
        children <- node.children
      } yield children.get("childA")
      whenReady(fChild) { child =>
        child.value.profile mustBe "broker/dataNode"
      }
    }
  }

  "AddValue" should {
    "append a value type child to a node" in {
      val node: DSANode = createNode("nodeB")
      val fChild = for {
        _ <- bindAndInvoke(node, StandardActions.AddValue, "Name" -> "childB", "Type" -> DSANumber)
        children <- node.children
      } yield children.get("childB")
      whenReady(fChild) { child =>
        child.value.profile mustBe "broker/dataNode"
        whenReady(child.value.valueType) { dsaType =>
          dsaType mustBe DSANumber
        }
      }
    }
  }

  "SetValue" should {
    "set the node value" in {
      val node: DSANode = createNode("nodeC")
      val fValue = for {
        _ <- bindAndInvoke(node, StandardActions.SetValue, "Value" -> true)
        value <- node.value
      } yield value
      whenReady(fValue) { value =>
        value mustBe BooleanValue(true)
      }
    }
  }

  "SetAttribute" should {
    "set a node attribute" in {
      val node: DSANode = createNode("nodeD")
      val fAttribute = for {
        _ <- bindAndInvoke(node, StandardActions.SetAttribute, "Name" -> "@abc", "Value" -> "123")
        attr <- node.attribute("@abc")
      } yield attr
      whenReady(fAttribute) { value =>
        value mustBe Some(StringValue("123"))
      }
    }
  }

  "SetConfig" should {
    "set a node config" in {
      val node: DSANode = createNode("nodeE")
      val fConfig = for {
        _ <- bindAndInvoke(node, StandardActions.SetConfig, "Name" -> "$abc", "Value" -> "123")
        cfg <- node.config("$abc")
      } yield cfg
      whenReady(fConfig) { value =>
        value mustBe Some(StringValue("123"))
      }
    }
  }

  "DeleteNode" should {
    "delete a child node" in {
      val node: DSANode = createNode("nodeF")
      val fChild = for {
        _ <- bindAndInvoke(node, StandardActions.AddNode, "Name" -> "childF")
        child <- node.child("childF")
        _ <- bindAndInvoke(child.get, StandardActions.DeleteNode) if child.isDefined
        child <- node.child("childF")
      } yield child
      whenReady(fChild) { child =>
        child mustBe None
      }
    }
  }

  "AddToken" should {
    "create a token node" in {
      val node: DSANode = createNode("nodeG")
      val fTuple = for {
        result <- bindAndInvoke(node, StandardActions.AddToken, "Group" -> "GroupX",
          "TimeRange" -> "20180101-20280101", "Count" -> 100, "MaxSession" -> 10, "Managed" -> true)
        (tokenId, token) = result.asInstanceOf[Option[(String, String)]].getOrElse(None)
        child <- node.child(tokenId.toString)
      } yield (tokenId, token, child)
      whenReady(fTuple) {
        case (tokenId, token, child) =>
          tokenId mustBe a[String]
          token mustBe a[String]
          tokenId.toString must have size 16
          token.toString must startWith(tokenId.toString)
          child.value.name mustBe tokenId
          child.value.profile mustBe "broker/token"
          whenReady(child.value.children) { children =>
            children.keys must contain allOf("count", "managed", "max_session", "role", "time_range", "token")
          }
      }
    }
  }

  "RegenerateToken" should {
    "update existing token node" in {
      val node: DSANode = createNode("nodeH")
      val fChildAndToken = node.addChild("token") map { child =>
        val token = Tokens.createToken()
        child.value = token
        (child, token)
      }
      val tuple = for {
        (child, token) <- fChildAndToken
        _ <- bindAndInvoke(node, StandardActions.RegenerateToken)
        newToken <- child.value
      } yield (token, newToken)
      whenReady(tuple) {
        case (oldToken, newToken) =>
          newToken mustBe a[StringValue]
          oldToken.take(16) mustBe newToken.toString.take(16)
          oldToken must not be newToken
      }
    }
  }

  "UpdateToken" should {
    "update $group config" in {
      val node: DSANode = createNode("nodeI")
      val fGroup = for {
        _ <- bindAndInvoke(node, StandardActions.UpdateToken, "Group" -> "xyz")
        group <- node.config("$group")
      } yield group
      whenReady(fGroup) { group =>
        group.value mustBe a[StringValue]
        group.value.toString mustBe "xyz"
      }
    }
  }

  "RemoveTokenClients" should {
    "remove dslink nodes referenced by the token" in {
      val downProbe = TestProbe()
      val linkProbe = TestProbe()
      system.actorOf(Props(new Actor {
        override def receive: Receive = {
          case GetOrCreateDSLink(name) =>
            val child = context.actorOf(TestActors.forwardActorProps(linkProbe.ref), name)
            sender ! ActorRefRoutee(child)
          case msg                     => downProbe.ref forward msg
        }
      }), Nodes.Downstream)
      val node: DSANode = createNode("nodeJ")
      node.addConfigs("$dsLinkIds" -> array("aaa", "bbb", "ccc"))
      val fCfg = for {
        _ <- bindAndInvoke(node, StandardActions.RemoveTokenClients)
        cfg <- node.config("$dsLinkIds")
      } yield cfg
      whenReady(fCfg) { cfg =>
        downProbe.expectMsgAllOf(RemoveDSLink("aaa"), RemoveDSLink("bbb"), RemoveDSLink("ccc"))
        linkProbe.expectMsgAllOf(DisconnectEndpoint(true), DisconnectEndpoint(true), DisconnectEndpoint(true))
        cfg mustBe None
      }
    }
  }

  "AddRoleNode" should {
    "create a new role child" in {
      val node: DSANode = createNode("nodeK")
      val fChild = for {
        _ <- bindAndInvoke(node, StandardActions.AddRoleNode, "Name" -> "aaa")
        children <- node.children
      } yield children.get("aaa")
      whenReady(fChild) { child =>
        child.value.profile mustBe "static"
      }
    }
  }

  "AddRuleNode" should {
    "create a new rule child" in {
      val path = "aaa/bbb/ccc"
      val node: DSANode = createNode("nodeL")
      val fChild = for {
        _ <- bindAndInvoke(node, StandardActions.AddRuleNode, "Path" -> path, "Permission" -> "read")
        children <- node.children
      } yield children.get(URLEncoder.encode(path, "UTF-8"))
      whenReady(fChild) { child =>
        child.value.profile mustBe "static"
        whenReady(child.value.value) { value =>
          value mustBe StringValue("read")
        }
      }
    }
  }

  private def createNode(name: String): DSANode = extension.typedActorOf(DSANode.props(None), name)

  private def bindAndInvoke(node: DSANode, action: DSAAction, params: (String, DSAVal)*) = for {
    actions <- Future.sequence(StandardActions.bindActions(node, ActionDescription("action", "Action", action)))
    actionNode = actions.head
    result = actionNode.invoke(params.toMap)
  } yield result
}
