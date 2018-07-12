package models.akka

import scala.concurrent.ExecutionContext.Implicits.global
import models.api._
import models.rpc.DSAValue

import scala.concurrent.Future
import models.rpc.DSAValue.{ArrayValue, DSAMap, DSAVal, MapValue, StringValue, array}
import java.net.URLEncoder

import akka.actor.{ActorSystem, TypedActor}
import models.akka.Messages.DisconnectEndpoint

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Standard node actions.
  */
object StandardActions {
  import DSAValueType._

//  implicit val system: ActorSystem = TypedActor.context.system

  case class ActionDescription(name:String, displayName:String, action: DSAAction
                               , invokable: Option[String] = Option("write")
                               , is: Option[String] = Option("node")
                              )

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

  def bindTokenGroupNodeActions(node: DSANode) =
    bindActions(node
      , ActionDescription("add", "Add token node", AddToken, Option("config"))
    )


  def bindTokenNodeActions(node: DSANode) =
    bindActions(node
      , ActionDescription("remove", "Remove token", DeleteNode, Option("config"))
      , ActionDescription("removeAllClients", "Remove clients", RemoveTokenClients, Option("config"))
      , ActionDescription("regenerate", "Regenerate", RegenerateToken, Option("config"))
      , ActionDescription("update", "Update token", UpdateToken, Option("config"))
    )

  /**
    * Adds actions to the 'Roles' node
    */
  def bindRolesNodeActions(node: DSANode) = {
    bindActions(node
      , ActionDescription("addRole", "Add permission group", AddRoleNode, Option("config"))
    )
  }

  def bindRoleNodeActions(node: DSANode) = {
    bindActions(node
      , ActionDescription("addRule", "Add rule", AddRuleNode, Option("config"))
      , ActionDescription("removeRole", "Remove group", DeleteNode, Option("config"))
    )
  }

  def bindRuleNodeActions(node: DSANode) = {
    bindActions(node
      , ActionDescription("removeRule", "Remove rule", DeleteNode
        , Option("config"))
    )
  }

  /**
    * Adds actions to the node as children.
    *
    */
  def bindActions(node: DSANode
                  , actions: ActionDescription*) = actions foreach {
    ad =>
      val params = ad.action.params filter
        { a =>
          a.getOrElse[DSAVal]("output", false).value == false
        } map
        { item => MapValue(item) }

      val columns = ad.action.params filter
        { a =>
          a.getOrElse[DSAVal]("output", false).value == true
        } map
        { item => MapValue(item) }

      val configs:Map[String, DSAVal] = Map(
        "$params" -> ArrayValue(params),
        "$invokable" -> ad.invokable.getOrElse("config"),
        "$is" -> ad.is.getOrElse("static"),
        "$name" -> ad.displayName,
        "$columns" -> ArrayValue(columns)
      )

      node.addChild(ad.name, configs.toSeq:_*).foreach { child =>
        child.action = ad.action
      }
  }

  /**
    * Adds a child node.
    */
  val AddNode: DSAAction = DSAAction((ctx: ActionContext) =>
    {
      ctx.node.parent foreach { parent =>
        parent.addChild(ctx.args("Name").value.toString, Some("broker/dataNode")) foreach { node =>
          bindDataNodeActions(node)
        }
      }
    }
    , Map[String, DSAVal]("name"->"Name", "type"-> DSAString)
  )

  /**
   * Adds a value child node.
   */
  val AddValue: DSAAction = DSAAction(ctx => {
      val parent = ctx.node.parent.get
      val name = ctx.args("Name").value.toString
      val dataType = ctx.args("Type").value.toString

      parent.addChild(name, Some("broker/dataNode")) foreach { node =>
        node.valueType = DSAValueType.withName(dataType)
        bindDataNodeActions(node)
      }
    }
    , Map[String, DSAVal]("name"->"Name", "type"-> DSAString)
    , Map[String, DSAVal]("name"->"Type", "type"-> DSAString)
  )

  /**
   * Sets the node value.
   */
  val SetValue: DSAAction = DSAAction((ctx: ActionContext) => {
      val node = ctx.node.parent.get
      val value = ctx.args("Value")

      node.value = value
    }
    , Map("name"->StringValue("Value"), "type"-> StringValue(DSADynamic.toString))
  )

  /**
    * Sets a node attibute.
    */
  val SetAttribute: DSAAction = DSAAction(ctx => {
      val node = ctx.node.parent.get
      val name = ctx.args("Name").value.toString
      val value = ctx.args("Value")

      node.addAttributes(name -> value)
    }
    , Map[String, DSAVal]("name"->"Name", "type"-> DSAString)
    , Map[String, DSAVal]("name"->"Value", "type"-> DSAString)
  )

  /**
    * Sets a node config.
    */
  val SetConfig: DSAAction = DSAAction(ctx => {
      val node = ctx.node.parent.get
      val name = ctx.args("Name").value.toString
      val value = ctx.args("Value")

      node.addConfigs(name -> value)
    }

    , Map[String, DSAVal]("name"->"Name", "type"-> DSAString)
    , Map[String, DSAVal]("name"->"Value", "type"-> DSAString)
  )

  /**
    * Deletes node.
    */
  val DeleteNode: DSAAction = DSAAction(ctx => {
    val node = ctx.node.parent.get
    node.parent foreach (_.removeChild(node.name))
  })

  val commonActions:Map[String, ActionDescription] = Map(
    ADD_NODE -> ActionDescription(ADD_NODE, "Add Node", AddNode, Option("config")),
    ADD_VALUE -> ActionDescription(ADD_VALUE, "Add Value", AddValue),
    SET_VALUE -> ActionDescription(SET_VALUE, "Set Value", SetValue),
    SET_ATTRIBUTE -> ActionDescription(SET_ATTRIBUTE, "Set Attribute", SetAttribute),
    SET_CONFIG -> ActionDescription(SET_CONFIG, "Set Config", SetConfig),
    DELETE_NODE -> ActionDescription(DELETE_NODE, "Delete Node", DeleteNode)
  )

  /**
    * Adds a Token node.
    * TODO: Check the case when 'Group' is not exist in the param
    * TODO: Add 'TimeRange', 'Count', 'Managed' params
    */
  val AddToken: DSAAction = DSAAction((ctx: ActionContext) => {

    val node = ctx.node.parent.get
    val groupName = ctx.args.getOrElse("Role", ctx.args("Group").value).toString
    val timeRange = ctx.args.getOrElse("TimeRange", "").toString
    val count = ctx.args.getOrElse("Count", "").toString
    val maxSession = ctx.args.getOrElse("MaxSession", "").toString
    val managed = ctx.args.getOrElse("Managed", "").toString

    val fToken: Future[String] = models.util.Tokens.makeToken(node);

    for (
      token <- fToken
    )
    {
      val tokenId = token.substring(0, 16);
      node.addChild(tokenId) foreach { child =>
        child.profile = "broker/TokenNode"
        child.addConfigs(
          ("group" -> groupName)
          , ("token" -> token)
          , ("is" -> "broker/Token")
        )
        child.addConfigs(
          ("$$count"->count)
          , ("$$managed"->managed)
          , ("$$maxSession"->maxSession)
          , ("$$timeRange"->timeRange)

        )
        bindTokenNodeActions(child)
      }
    }

    val token = Await.result(fToken, Duration.Inf)

    (token.substring(0, 16), token)
  }
    , Map[String, DSAVal]("name"->"Group", "type"->DSAString, "editor"->"enum[none,list,read,write,config]")
    , Map[String, DSAVal]("name"->"TimeRange", "type"->DSAString, "editor"->"daterange", "writable"->"config")
    , Map[String, DSAVal]("name"->"Count", "type"->DSANumber)
    , Map[String, DSAVal]("name"->"MaxSession", "type"->DSANumber)
    , Map[String, DSAVal]("name"->"Managed", "type"->DSABoolean)
    , Map[String, DSAVal]("name"->"TokenName", "type"->DSAString, "output"->true)
    , Map[String, DSAVal]("name"->"Token", "type"->DSAString, "output"->true)
  )

  /**
    * Regenerate the token, first 18 bytes became the same, rest ones
    * are regenerated
    */
  val RegenerateToken: DSAAction = DSAAction((ctx: ActionContext) => {
    val node = ctx.node.parent.get

    val fToken = node.config("token")
    for (oToken <- fToken) {
      oToken foreach { token =>
        val newToken = models.util.Tokens.regenerate(token.toString)
        node.addConfigs("token" -> newToken)
      }
    }
  }
  )

  /**
    * Modify current token node
    */
  val UpdateToken: DSAAction = DSAAction((ctx: ActionContext) =>
    {
      val node = ctx.node.parent.get
      val group = ctx.args("Group").value.toString

      node.addConfigs("group" -> group)
    }
    , Map[String, DSAVal]("name"->"Group", "type"-> DSAString, "editor"->"enum[none,list,read,write,config]")
  )

  /**
    * Remove all clients related to the token. Token is the same
    */
  val RemoveTokenClients: DSAAction = DSAAction((ctx: ActionContext) => {
    val node = ctx.node.parent.get

    val fids = node.config("dsLinkIds")

    fids foreach {
      case Some(x) =>
        val arrDsId = x.asInstanceOf[ArrayValue].value
        arrDsId foreach { dsId =>
          println(dsId)
          val dstActorRef = RootNodeActor.childProxy("/downstream/" + dsId.toString)(TypedActor.context.system)
          dstActorRef ! DisconnectEndpoint(true)
        }

      case None =>

    }
  }
  )

  /**
    * Adds new ROLE (aka group) node
    */
  val AddRoleNode: DSAAction = DSAAction((ctx: ActionContext) =>
    ctx.node.parent foreach { parent =>
      val roleName = ctx.args("Name").value.toString
      parent.addChild(roleName, Some("node")) foreach { child =>
        bindRoleNodeActions(child)
      }
    }
    , Map[String, DSAVal]("name"->"Name", "type"->DSAString)
  )

  /**
    * Adds new RULE to the node
    */
  val AddRuleNode: DSAAction = DSAAction ((ctx: ActionContext) =>
    ctx.node.parent foreach { parent =>
      val path = ctx.args("Path").value.toString
      val perm = ctx.args("Permission")

      parent.addChild(URLEncoder.encode(path, "UTF-8"), "$permission"->perm) foreach { ruleNode =>
        bindActions(ruleNode
          , ActionDescription("removeRule", "Remove rule", DeleteNode
            , Option("config"))
        )
      }
    }
    , Map[String, DSAVal]("name"->"Path", "type"-> DSAString)
    , Map[String, DSAVal]("name"->"Permission", "type"-> DSADynamic
      , "editor"->"enum[none,list,read,write,config]")
  )

}
