package models.akka

import scala.concurrent.ExecutionContext.Implicits.global
import models.api.{DSAAction, DSANode, DSAValueType}
import models.rpc.DSAValue
import models.rpc.DSAValue.{DSAVal, obj}

/**
  * Standard node actions.
  */
object StandardActions {
  import DSAValueType._

  case class ActionDescription(name:String, displayName:String, action:DSAAction)

  val ADD_NODE = "addNode"
  val ADD_VALUE = "addValue"
  val SET_VALUE = "setValue"
  val SET_ATTRIBUTE = "setAttribute"
  val SET_CONFIG = "setConfig"
  val DELETE_NODE = "deleteNode"


  /**
    * Adds actions as per broker/dataRoot profile.
    */
  def bindDataRootActions(node: DSANode) = {
    bindActions(node,
      commonActions(ADD_NODE),
      commonActions(ADD_VALUE)
    )
  }

  /**
    * Adds actions as per broker/dataNode profile.
    */
  def bindDataNodeActions(node: DSANode) = bindActions(node,
    commonActions(ADD_NODE),
    commonActions(ADD_VALUE),
    commonActions(SET_VALUE),
    commonActions(SET_ATTRIBUTE),
    commonActions(SET_CONFIG),
    commonActions(DELETE_NODE)
  )

  /**
    * Adds actions to the node as children.
    */
  def bindActions(node: DSANode, actions: ActionDescription*) = actions foreach {
    ad =>
      val params = ad.action.params map {
        case (pName, pType) => obj("name" -> pName, "type" -> pType.toString)
      }
      val configs:Map[String, DSAVal] = Map(
        "$params" -> params,
        "$invokable" -> "write",
        "$is" -> "static",
        "$name" -> ad.displayName
      )

      node.addChild(ad.name, configs.toSeq:_*).foreach { child =>
        child.action = ad.action
    }
  }

  /**
    * Adds a child node.
    */
  val AddNode: DSAAction = DSAAction(ctx => {
    ctx.node.parent foreach { parent =>
      parent.addChild(ctx.args("name").value.toString, Some("broker/dataNode")) foreach { node =>
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

    parent.addChild(name, Some("broker/dataNode")) foreach { node =>
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


  val commonActions:Map[String, ActionDescription] = Map(
    ADD_NODE -> ActionDescription(ADD_NODE, "Add Node", AddNode),
    ADD_VALUE -> ActionDescription(ADD_VALUE, "Add Value", AddValue),
    SET_VALUE -> ActionDescription(SET_VALUE, "Set Value", SetValue),
    SET_ATTRIBUTE -> ActionDescription(SET_ATTRIBUTE, "Set Attribute", SetAttribute),
    SET_CONFIG -> ActionDescription(SET_CONFIG, "Set Config", SetConfig),
    DELETE_NODE -> ActionDescription(DELETE_NODE, "Delete Node", DeleteNode)
  )
}