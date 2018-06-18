package models.akka

import scala.concurrent.ExecutionContext.Implicits.global
import models.api.{DSAAction, DSANode, DSAValueType}
import models.rpc.DSAValue

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
    ("addValue", "Add Value", AddValue))

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

  /**
    * Adds actions to the node as children.
    */
  def bindActions(node: DSANode, actions: (String, String, DSAAction)*) = actions foreach {
    case (name, displayName, action) => node.addChild(name).foreach { child =>
      child.displayName = displayName
      child.action = action
      child.profile = "node"
      child.addConfigs("$invokable" -> "write")

      val params = action.params map{ p =>
        DSAValue.obj(
          ("name", p._1),
          ("type", p._2.toString)
        )
      }


      child.addConfigs("$params" -> DSAValue.array(params:_*))
    }
  }

  /**
   * Adds a child node.
   */
  val AddNode: DSAAction = DSAAction(ctx => {
    ctx.node.parent foreach { parent =>
      parent.addChild(ctx.args("name").value.toString) foreach { node =>
        node.profile = "broker/dataNode"
        bindDataNodeActions(node)
      }
    }
  }, "name" -> DSAString)

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
  val SetValue: DSAAction = DSAAction(ctx => {
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