package models.akka

import java.net.URLEncoder

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.routing.Routee
import akka.util.Timeout
import models.Settings.Paths
import models.akka.Messages.{DisconnectEndpoint, GetOrCreateDSLink, RemoveDSLink}
import models.api._
import models.rpc.DSAValue.{ArrayValue, DSAVal, StringValue}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
  * Standard node actions.
  */
object StandardActions {

  import DSAValueType._

  case class ActionDescription(name: String, displayName: String, action: DSAAction,
                               invokable: Option[String] = Some("config"), is: Option[String] = None)

  /* common action names */

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

  implicit val timeout: Timeout = 20 seconds

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
    * Adds standard actions for token group node.
    *
    * @param node
    */
  def bindTokenGroupNodeActions(node: DSANode) = bindActions(node,
    commonActions(ADD_TOKEN)
  )

  /**
    * Adds standard actions for token node.
    *
    * @param node
    */
  def bindTokenNodeActions(node: DSANode) = bindActions(node,
    commonActions(REMOVE_TOKEN),
    commonActions(TOKEN_REMOVE_CLIENTS),
    commonActions(REGENERATE_TOKEN)
  )

  /**
    * Adds actions to the 'Roles' node
    */
  def bindRolesNodeActions(node: DSANode) = bindActions(node,
    commonActions(ADD_ROLE)
  )

  /**
    * Adds standard actions for role node.
    *
    * @param node
    */
  def bindRoleNodeActions(node: DSANode) = bindActions(node,
    commonActions(ADD_RULE),
    commonActions(REMOVE_ROLE)
  )

  /**
    * Adds standard actions for rule node.
    *
    * @param node
    */
  def bindRuleNodeActions(node: DSANode) = bindActions(node,
    commonActions(REMOVE_RULE)
  )

  /**
    * Adds actions to the node as children.
    */
  def bindActions(node: DSANode, actions: ActionDescription*) = actions map { ad =>

    val params = ad.action.params collect {
      case p @ Param(_, _, _, _, false) => p.toMapValue
    }

    val columns = ad.action.params collect {
      case p @ Param(_, _, _, _, true) => p.toMapValue
    }

    val configs = Map(
      "$params" -> ArrayValue(params),
      "$name" -> (ad.displayName: DSAVal),
      "$columns" -> ArrayValue(columns),
      "$is" -> (ad.is.getOrElse(""): DSAVal)
    ) ++ (ad.invokable map (i => "$invokable" -> (i: DSAVal)))

    node.addChild(ad.name, configs.toSeq: _*) map { child =>
      child.action = ad.action
      child
    }
  }

  /**
    * Adds a child node.
    */
  val AddNode: DSAAction = DSAAction((ctx: ActionContext) => {
    ctx.node.parent foreach { parent =>
      parent.addChild(ctx.args("Name").value.toString, Some("broker/dataNode")) foreach { node =>
        bindDataNodeActions(node)
      }
    }
  }, Param("Name", DSAString))

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
  }, Param("Name", DSAString), Param("Type", DSAString))

  /**
    * Sets the node value.
    */
  val SetValue: DSAAction = DSAAction((ctx: ActionContext) => {
    val node = ctx.node.parent.get
    val value = ctx.args("Value")

    node.value = value
  }, Param("Value", DSADynamic))

  /**
    * Sets a node attibute.
    */
  val SetAttribute: DSAAction = DSAAction(ctx => {
    val node = ctx.node.parent.get
    val name = ctx.args("Name").value.toString
    val value = ctx.args("Value")

    node.addAttributes(name -> value)
  }, Param("Name", DSAString), Param("Value", DSAString))

  /**
    * Sets a node config.
    */
  val SetConfig: DSAAction = DSAAction(ctx => {
    val node = ctx.node.parent.get
    val name = ctx.args("Name").value.toString
    val value = ctx.args("Value")

    node.addConfigs(name -> value)
  }, Param("Name", DSAString), Param("Value", DSAString))

  /**
    * Deletes node.
    */
  val DeleteNode: DSAAction = DSAAction(ctx => {
    val node = ctx.node.parent.get
    node.parent foreach (_.removeChild(node.name))
  })

  /**
    * Adds a Token node and returns a pair (tokenId, token) where tokenId is the first 16 characters of the token.
    */
  val AddToken: DSAAction = DSAAction((ctx: ActionContext) => {

    val node = ctx.node.parent.get
    val groupName = ctx.args.get("Role").orElse(ctx.args.get("Group")).getOrElse("").toString
    val timeRange = ctx.args.getOrElse("TimeRange", "").toString
    val count = ctx.args.getOrElse("Count", "").toString
    val maxSession = ctx.args.getOrElse("MaxSession", "").toString
    val managed = ctx.args.getOrElse("Managed", "").toString

    // TODO: there is a chance of a racing condition here, needs to be rewritten as one atomic operation
    val fToken = models.util.Tokens.makeTokenForNode(node)
    fToken foreach { token =>
      val tokenId = token.take(16)
      node.addChild(tokenId) foreach { child =>
        initTokenNode(child, token, groupName, timeRange, count, maxSession, managed)
      }
    }

    // TODO: Change it to non-blocking way
    val token = Await.result(fToken, Duration.Inf)
    (token.take(16), token)
  },
    Param("Group", DSAString),
    Param("TimeRange", DSAString) withEditor "daterange" writableAs "config",
    Param("Count", DSANumber),
    Param("MaxSession", DSANumber),
    Param("Managed", DSABoolean),
    Param("TokenName", DSAString) asOutput(),
    Param("Token", DSAString) asOutput()
  )

  /**
    * Initializes a token node.
    *
    * @param node
    * @param token
    * @param groupName
    * @param timeRange
    * @param count
    * @param maxSession
    * @param managed
    * @return
    */
  def initTokenNode(node: DSANode, token: String, groupName: String, timeRange: String = "", count: String = "",
                    maxSession: String = "", managed: String = "") = {

    node.profile = "broker/tokenNode"
    node.addConfigs("$is" -> "broker/token")

    bindTokenNodeActions(node)

    def typeCfg(dsaType: DSAValueType): (String, DSAVal) = "$type" -> dsaType

    def writableCfg(writable: String): (String, DSAVal) = "$writable" -> writable

    node.addChild("count", typeCfg(DSANumber), writableCfg("config")) foreach {
      _.value = count
    }
    node.addChild("managed", typeCfg(DSABoolean), writableCfg("config")) foreach {
      _.value = managed
    }
    node.addChild("max_session", typeCfg(DSANumber), writableCfg("config")) foreach {
      _.value = maxSession
    }
    node.addChild("role", typeCfg(DSAString), writableCfg("config")) foreach {
      _.value = groupName
    }
    node.addChild("time_range", typeCfg(DSAString), writableCfg("config")) map {
      _.value = timeRange
    }
    node.addChild("token", typeCfg(DSAString)) map {
      _.value = token
    }
  }

  /**
    * Regenerate the token: the first 16 bytes became the same, the rest are regenerated.
    */
  val RegenerateToken: DSAAction = DSAAction((ctx: ActionContext) => {
    val node = ctx.node.parent.get

    val fToken = node.child("token")
    for (oToken <- fToken) {
      oToken foreach { token =>
        token.value foreach { value123 =>
          token.value = models.util.Tokens.regenerate(value123.toString)
        }
      }
    }
  })

  /**
    * Sets "$group" attribute of the token node.
    */
  val UpdateToken: DSAAction = DSAAction((ctx: ActionContext) => {
    val node = ctx.node.parent.get
    val group = ctx.args("Group").value.toString

    node.addConfigs("$group" -> group)
  }, Param("Group", DSAString))

  /**
    * Remove all clients related to the token.
    */
  val RemoveTokenClients: DSAAction = DSAAction((ctx: ActionContext) => {
    val node = ctx.node.parent.get

    // TODO: this needs to be refactored either by IoC or passing AS explicitly
    implicit val system: ActorSystem =
      if (ctx.node.isInstanceOf[DistributedDSANode])
        ctx.node.asInstanceOf[DistributedDSANode]._system
      else
        ctx.node.asInstanceOf[InMemoryDSANode].system

    val fDsIds = node.config("$dsLinkIds") map { cfg =>
      cfg.toSeq.collect {
        case arr: ArrayValue => arr.value.map(_.toString)
      }.flatten
    }

    val dstActorRef = system.actorSelection("/user" + Paths.Downstream)

    fDsIds foreach (_.foreach { dsId =>
      val dstName = if (dsId.size > 44) dsId.take(dsId.size - 44) else dsId
      val fRoutee = (dstActorRef ? GetOrCreateDSLink(dstName)).mapTo[Routee]
      fRoutee foreach { routee =>
        routee ! DisconnectEndpoint(true)
        dstActorRef ! RemoveDSLink(dstName)
      }
    })

    node.removeConfig("$dsLinkIds")
  })

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
    }, Param("Name", DSAString))

  /**
    * Adds new RULE to the node
    */
  val AddRuleNode: DSAAction = DSAAction((ctx: ActionContext) =>
    ctx.node.parent foreach { parent =>
      val path = ctx.args("Path").value.toString
      val perm = ctx.args("Permission")

      parent.addChild(URLEncoder.encode(path, "UTF-8"), RootNodeActor.DEFAULT_RULE_CONFIG.toList: _*) foreach {
        ruleNode =>
          ruleNode.value = perm.toString
          ruleNode.profile = "static"
          bindActions(ruleNode, commonActions(REMOVE_RULE))
      }
    }, Param("Path", DSAString), Param("Permission", DSADynamic) withEditor "enum[none,list,read,write,config]")

  val commonActions: Map[String, ActionDescription] = Map(
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
    ADD_ROLE -> ActionDescription(ADD_ROLE, "Add permission group", AddRoleNode, Option("config")),
    ADD_RULE -> ActionDescription(ADD_RULE, "Add rule", AddRuleNode, Option("config")),
    REMOVE_ROLE -> ActionDescription(REMOVE_ROLE, "Remove group", DeleteNode, Option("config")),
    REMOVE_RULE -> ActionDescription(REMOVE_RULE, "Remove rule", DeleteNode, Option("config"))
  )
}