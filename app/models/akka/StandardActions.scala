package models.akka

import scala.concurrent.ExecutionContext.Implicits.global
import models.api.{ActionContext, DSAAction, DSANode, DSAValueType}

import scala.concurrent.Future

/**
 * Standard node actions.
 */
object StandardActions {
  import DSAValueType._

  /**
   * Adds actions as per broker/dataRoot profile.
   */
  def bindDataRootActions(node: DSANode) = bindActions(node,
    ("addNode", "Add Node", AddNode),
    ("addValue", "Add VSalue", AddValue))

  /**
   * Adds actions as per broker/dataNode profile.
   */
  def bindDataNodeActions(node: DSANode) = bindActions(node,
    ("addNode", "Add Node", AddNode),
    ("addValue", "Add Value", AddValue),
    ("setValue", "Set Value", SetValue),
    ("setAttribute", "Set Attribute", SetAttribute),
    ("setConfig", "Set Config", SetConfig),
    ("deleteNode", "Delete Node", DeleteNode))

  def bindTokenNodeActions(node: DSANode) = bindActions(node,
    ("delete", "Delete token", DeleteNode)
    , ("update", "Update token", UpdateToken)
  )

  def bindTokenGroupNodeActions(node: DSANode) = bindActions(node,
    ("addToken", "Add token node", AddToken)
  )

  /**
   * Adds actions to the node as children.
   */
  def bindActions(node: DSANode, actions: (String, String, DSAAction)*) = actions foreach {
    case (name, displayName, action) => node.addChild(name).foreach { child =>
      child.displayName = displayName
      child.action = action
    }
  }

  /**
   * Adds a child node.
   */
  val AddNode: DSAAction = DSAAction((ctx: ActionContext) => {
    ctx.node.parent foreach { parent =>
      parent.addChild(ctx.args("name").value.toString) foreach { node =>
        node.profile = "broker/dataNode"
        bindDataNodeActions(node)
      }
    }
  }, "name" -> DSAString)

  /**
    * Adds a Token node.
    * TODO: Check the case when 'Group' is not exist in the param
    * TODO: Add 'TimeRange', 'Count', 'Managed' params
    */
  val AddToken: DSAAction = DSAAction((ctx: ActionContext) => {

    val node = ctx.node
    val groupName = ctx.args("Group").value.toString

    val fToken: Future[String] = models.util.Tokens.makeToken(node);

    for (
      token <- fToken
    )
    {
      val tokenId = token.substring(0, 16);
      node.addChild(tokenId) foreach { child =>
        child.profile = "broker/TokenNode"
        child.addConfigs("$$group" -> groupName)
        child.addConfigs("$$token" -> token)
        bindTokenNodeActions(child)
      }
    }

  }, "Group" -> DSAString)

  /**
    * Modify current token node
    */
  val UpdateToken: DSAAction = DSAAction((ctx: ActionContext) => {
    val node = ctx.node
    val group = ctx.args("Group")

    node.addConfigs("$$group" -> group)

  }, "Group" -> DSAString)

  /**
   * Adds a value child node.
   */
  val AddValue: DSAAction = DSAAction(ctx => {
    val parent = ctx.node.parent.get
    val name = ctx.args("name").value.toString
    val dataType = ctx.args("type").value.toString

    parent.addChild(name) foreach { node =>
      node.profile = "broker/dataNode"
      node.valueType = DSAValueType.withName(dataType)
      bindDataNodeActions(node)
    }
  }, "name" -> DSAString, "type" -> DSAString)

  /**
   * Sets the node value.
   */
  val SetValue: DSAAction = DSAAction((ctx: ActionContext) => {
    val node = ctx.node.parent.get
    val value = ctx.args("value")

    node.value = value
  }, "value" -> DSADynamic)

  /**
   * Sets a node attibute.
   */
  val SetAttribute: DSAAction = DSAAction(ctx => {
    val node = ctx.node.parent.get
    val name = ctx.args("name").value.toString
    val value = ctx.args("value")

    node.addAttributes(name -> value)
  }, "name" -> DSAString, "value" -> DSADynamic)

  /**
   * Sets a node config.
   */
  val SetConfig: DSAAction = DSAAction(ctx => {
    val node = ctx.node.parent.get
    val name = ctx.args("name").value.toString
    val value = ctx.args("value")

    node.addConfigs(name -> value)
  }, "name" -> DSAString, "value" -> DSADynamic)

  /**
   * Deletes node.
   */
  val DeleteNode: DSAAction = DSAAction(ctx => {
    val node = ctx.node.parent.get
    node.parent foreach (_.removeChild(node.name))
  })
}