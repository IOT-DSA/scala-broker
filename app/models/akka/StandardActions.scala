package models.akka

import scala.concurrent.ExecutionContext.Implicits.global
import models.api._

import scala.concurrent.Future
import models.rpc.DSAValue.{ArrayValue, DSAVal, MapValue, StringValue}
import java.net.URLEncoder

import models.Settings.Paths
import models.akka.Messages.{DisconnectEndpoint, GetOrCreateDSLink, RemoveDSLink}

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.routing.Routee
import akka.util.Timeout


/**
  * Standard node actions.
  */
object StandardActions {
  import DSAValueType._



  case class ActionDescription(name:String, displayName:String, action: DSAAction
                               , invokable: Option[String] = Some("config")
                               , is: Option[String] = None
                              )

  val ADD_NODE = "addNode"
  val ADD_VALUE = "addValue"
  val SET_VALUE = "setValue"
  val SET_ATTRIBUTE = "setAttribute"
  val SET_CONFIG = "setConfig"
  val DELETE_NODE = "deleteNode"

  val ADD_TOKEN = "add"
  val REMOVE_TOKEN = "remove"
  val TOKEN_REMOVE_CLIENTS = "removeAllClients"
  val REGENERATE_TOKEN = "regenerate"
  val UPDATE_TOKEN = "update"

  val ADD_ROLE = "addRole"
  val ADD_RULE = "addRule"
  val REMOVE_ROLE = "removeRole"
  val REMOVE_RULE = "removeRule"

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
      , commonActions(ADD_TOKEN)
    )


  def bindTokenNodeActions(node: DSANode) =
    bindActions(node
      , commonActions(REMOVE_TOKEN)
      , commonActions(TOKEN_REMOVE_CLIENTS)
      , commonActions(REGENERATE_TOKEN)
//      , commonActions(UPDATE_TOKEN)
    )

  /**
    * Adds actions to the 'Roles' node
    */
  def bindRolesNodeActions(node: DSANode) = {
    bindActions(node
      , commonActions(ADD_ROLE)
    )
  }

  def bindRoleNodeActions(node: DSANode) = {
    bindActions(node
      , commonActions(ADD_RULE)
      , commonActions(REMOVE_ROLE)
    )
  }

  def bindRuleNodeActions(node: DSANode) = {
    bindActions(node
      , commonActions(REMOVE_RULE)
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

      var configs:Map[String, DSAVal] = Map(
        "$params" -> ArrayValue(params),
        "$name" -> ad.displayName,
        "$columns" -> ArrayValue(columns),
        "$is" -> ""
      )
      ad.is foreach { v => configs += ("$is"-> StringValue(v)) }
      ad.invokable foreach { v => configs += ("$invokable"->StringValue(v)) }

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

  /**
    * Adds a Token node.
    * TODO: Check the case when 'Group' is not exist in the param
    * TODO: Add 'TimeRange', 'Count', 'Managed' params
    */
  val AddToken: DSAAction = DSAAction(
    (ctx: ActionContext) => {

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
        createTokenNode(node, token, groupName, timeRange, count, maxSession, managed)
      }

      // TODO: Change it to non-blocking way
      val token = Await.result(fToken, Duration.Inf)

      (token.substring(0, 16), token)
    }
    , Map[String, DSAVal]("name"->"Group", "type"->DSAString)
    , Map[String, DSAVal]("name"->"TimeRange", "type"->DSAString, "editor"->"daterange"
      , "writable"->"config")
    , Map[String, DSAVal]("name"->"Count", "type"->DSANumber)
    , Map[String, DSAVal]("name"->"MaxSession", "type"->DSANumber)
    , Map[String, DSAVal]("name"->"Managed", "type"->DSABoolean)
    , Map[String, DSAVal]("name"->"TokenName", "type"->DSAString, "output"->true)
    , Map[String, DSAVal]("name"->"Token", "type"->DSAString, "output"->true)
  )

  def createTokenNode(node: DSANode, token: String, groupName: String, timeRange: String="", count: String=""
                      , maxSession: String="", managed: String="") =
  {
    val tokenId = token.substring(0, 16);

    node.addChild(tokenId) foreach { child =>
      initTokenNode(child, token, groupName, timeRange, count, maxSession, managed)
    }
  }

  def initTokenNode(node: DSANode, token: String, groupName: String, timeRange: String = "", count: String = "", maxSession: String = "", managed: String = "") = {
    node.profile = "broker/tokenNode"
    node.addConfigs(RootNodeActor.DEFAULT_TOKEN_CONFIG.toList: _*)

    bindTokenNodeActions(node)

    node.addChild("count", (RootNodeActor.DEFAULT_TOKEN_CHILD_CONFIG + ("$type" -> StringValue("number"))).toList: _*) foreach {
      childNode =>
        childNode.value = count
    }
    node.addChild("managed", (RootNodeActor.DEFAULT_TOKEN_CHILD_CONFIG + ("$type" -> StringValue("bool"))).toList: _*) foreach {
      childNode =>
        childNode.value = managed
    }
    node.addChild("max_session", (RootNodeActor.DEFAULT_TOKEN_CHILD_CONFIG + ("$type" -> StringValue("number"))).toList: _*) foreach {
      childNode =>
        childNode.value = maxSession
    }
    node.addChild("role", (RootNodeActor.DEFAULT_TOKEN_CHILD_CONFIG).toList: _*) foreach {
      childNode =>
        childNode.value = groupName
    }
    node.addChild("time_range", (RootNodeActor.DEFAULT_TOKEN_CHILD_CONFIG).toList: _*) foreach {
      childNode =>
        childNode.value = timeRange
    }
    node.addChild("token", (RootNodeActor.DEFAULT_TOKEN_CHILD_CONFIG - "writable").toList: _*) foreach {
      childNode =>
        childNode.value = token
    }
  }

  /**
    * Regenerate the token, first 18 bytes became the same, rest ones
    * are regenerated
    */
  val RegenerateToken: DSAAction = DSAAction((ctx: ActionContext) => {
    val node = ctx.node.parent.get

    val fToken = node.child("token")
    for (oToken <- fToken) {
      oToken foreach { token =>
        token.value foreach { value123 =>
          val newToken = models.util.Tokens.regenerate(value123.toString)
          token.value = newToken
        }
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
    , Map[String, DSAVal]("name"->"Group", "type"-> DSAString)
  )

  implicit val duration: Timeout = 20 seconds

  /**
    * Remove all clients related to the token. Token is the same
    */
  val RemoveTokenClients: DSAAction = DSAAction((ctx: ActionContext) => {
    val node = ctx.node.parent.get

    val fids = node.config("$dsLinkIds")

    implicit val system: ActorSystem =
      if (ctx.node.isInstanceOf[DistributedDSANode])
        ctx.node.asInstanceOf[DistributedDSANode]._system
      else
        ctx.node.asInstanceOf[InMemoryDSANode].system

    fids foreach {
      case Some(x) =>
        val arrDsId = x.asInstanceOf[ArrayValue].value
        arrDsId foreach { dsId =>
          val dstName = if (dsId.toString.size > 44)
            dsId.toString.substring(0, dsId.toString.size - 44)
            else dsId.toString

          // TODO: Add exception handling here
          val dstActorRef = system.actorSelection("/user" + Paths.Downstream)

          val fRoutee = (dstActorRef ? GetOrCreateDSLink(dstName)).mapTo[Routee]

          fRoutee foreach { routee =>
            routee ! DisconnectEndpoint(true)
            dstActorRef ! RemoveDSLink(dstName)
          }
        }

      case None =>
    }
    node.removeConfig("$dsLinkIds")
  }
  )

  /**
    * Adds new ROLE (aka group) node
    */
  val AddRoleNode: DSAAction = DSAAction((ctx: ActionContext) =>
    ctx.node.parent foreach { parent =>
      val roleName = ctx.args("Name").value.toString
      parent.addChild(roleName, RootNodeActor.DEFAULT_ROLE_CONFIG.toList: _*) foreach { child =>
        child.profile = "static"
        bindRoleNodeActions(child)
        RootNodeActor.createFallbackRole(child)
      }
    }
    // TODO: Add exception handling here!
//        .onComplete(
//        {
//          case Success(_) => {
//            //Do something with my list
//          }
//          case Failure(e) => {
//            println("Error while adding new rule: " + e.getMessage)
//            //Do something with my error
//          }
//        }
//      )

//    recover {
//      case e: RuntimeException => println("Error while adding new rule: " + e.getMessage)
//    }
    , Map[String, DSAVal]("name"->"Name", "type"->DSAString)
  )

  /**
    * Adds new RULE to the node
    */
  val AddRuleNode: DSAAction = DSAAction ((ctx: ActionContext) =>
    ctx.node.parent foreach { parent =>
      val path = ctx.args("Path").value.toString
      val perm = ctx.args("Permission")

      parent.addChild(URLEncoder.encode(path, "UTF-8"), RootNodeActor.DEFAULT_RULE_CONFIG.toList:_*) foreach {
        ruleNode =>
        ruleNode.value = perm.toString
        ruleNode.profile = "static"
        bindActions(ruleNode, commonActions(REMOVE_RULE))
      }
      // TODO: Add exception handling here!
//      recover {
//        case e: RuntimeException => println("Error while adding new rule: " + e.getMessage)
//      }
    }
    , Map[String, DSAVal]("name"->"Path", "type"-> DSAString)
    , Map[String, DSAVal]("name"->"Permission", "type"-> DSADynamic
    , "editor"->"enum[none,list,read,write,config]")
  )

  val commonActions:Map[String, ActionDescription] = Map(
    ADD_NODE -> ActionDescription(ADD_NODE, "Add Node", AddNode, Option("config")),
    ADD_VALUE -> ActionDescription(ADD_VALUE, "Add Value", AddValue),
    SET_VALUE -> ActionDescription(SET_VALUE, "Set Value", SetValue),
    SET_ATTRIBUTE -> ActionDescription(SET_ATTRIBUTE, "Set Attribute", SetAttribute),
    SET_CONFIG -> ActionDescription(SET_CONFIG, "Set Config", SetConfig),
    DELETE_NODE -> ActionDescription(DELETE_NODE, "Delete Node", DeleteNode),
    ADD_TOKEN -> ActionDescription(ADD_TOKEN, "Add token node", AddToken, Option("config")),
    REMOVE_TOKEN -> ActionDescription(REMOVE_TOKEN, "Remove token", DeleteNode, Option("config")),
    TOKEN_REMOVE_CLIENTS -> ActionDescription(TOKEN_REMOVE_CLIENTS, "Remove clients", RemoveTokenClients, Option("config")),
    REGENERATE_TOKEN -> ActionDescription(REGENERATE_TOKEN, "Regenerate", RegenerateToken, Option("config")),
//    UPDATE_TOKEN -> ActionDescription(UPDATE_TOKEN, "Update token", UpdateToken, Option("config")),
    ADD_ROLE -> ActionDescription(ADD_ROLE, "Add permission group", AddRoleNode, Option("config")),
    ADD_RULE -> ActionDescription(ADD_RULE, "Add rule", AddRuleNode, Option("config")),
    REMOVE_ROLE -> ActionDescription(REMOVE_ROLE, "Remove group", DeleteNode, Option("config")),
    REMOVE_RULE -> ActionDescription(REMOVE_RULE, "Remove rule", DeleteNode, Option("config"))
  )
}
