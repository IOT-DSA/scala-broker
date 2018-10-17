package models.api

import akka.actor.{ActorRef, ActorSystem, TypedActor}
import akka.event.Logging
import akka.pattern._
import akka.util.Timeout
import models.akka.Messages._
import models.akka.RootNodeActor
import models.api.DSAValueType.{DSADynamic, DSAValueType}
import models.rpc.DSAValue.{ArrayValue, DSAMap, DSAVal, StringValue, array, obj}
import models.util.DsaToAkkaCoder._
import models.util.{LoggingAdapterInside, Tokens}
import models.{RequestEnvelope, ResponseEnvelope, Settings}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * DSA Node actor-based in memory implementation.
  */
class InMemoryDSANode(val parent: Option[DSANode])
  extends DSANode
    with DSANodeRequestHandler
    with LoggingAdapterInside
    with DSANodeSubscriptions
    with TypedActor.Receiver
    with TypedActor.PreStart
    with TypedActor.PostStop {

  protected val log = Logging(TypedActor.context.system, getClass)

  val name = TypedActor.context.self.path.name.forDsa
  val path = parent.map(_.path).getOrElse("") + "/" + name

  protected def ownId = s"[$path]"

  override implicit val executionContext: ExecutionContext = TypedActor.context.dispatcher

  val system = TypedActor.context.system

  implicit val timeout = Timeout(10 seconds)

  private var _value: DSAVal = _

  def value = Future.successful(_value)

  def value_=(v: DSAVal) = {
    _value = v
    log.debug(s"$ownId: changed value to $v")
    notifySubscribeActors(v)
  }

  def valueType = config("$type").map(_.map(s => DSAValueType.withName(s.value.toString)).getOrElse(DSADynamic))

  def valueType_=(vt: DSAValueType) = addConfigs("$type" -> vt.toString)

  def displayName = config("$name").map(_.map(_.value.toString).getOrElse(name))

  def displayName_=(name: String) = addConfigs("$name" -> name)

  def profile = Await.result(config("$is").map(_.map(_.value.toString).getOrElse("node")), Duration.Inf)

  def profile_=(p: String) = addConfigs("$is" -> p)

  protected var _configs = Map[String, DSAVal]("$is" -> "node")

  def configs = Future.successful(_configs)

  def config(name: String) = configs map (_.get(name))

  def addConfigs(configs: (String, DSAVal)*) = {
    val cfg = configs map {
      case (name, value) => (if (name.startsWith("$")) name else "$" + name) -> value
    }
    _configs ++= cfg
    log.debug(s"$ownId: added configs $cfg")
    notifyListActors(cfg map (c => array(c._1, c._2)): _*)
  }

  def removeConfig(name: String) = {
    _configs -= name
    log.debug(s"$ownId: removed config '$name'")
    notifyListActors(obj("name" -> name, "change" -> "remove"))
  }

  protected var _attributes = Map.empty[String, DSAVal]

  def attributes = Future.successful(_attributes.toMap)

  def attribute(name: String) = attributes map (_.get(name))

  def addAttributes(attributes: (String, DSAVal)*) = {
    val attrs = attributes map {
      case (name, value) => (if (name.startsWith("@")) name else "@" + name) -> value
    }
    _attributes ++= attrs
    log.debug(s"$ownId: added attributes $attrs")
    notifyListActors(attrs map (a => array(a._1, a._2)): _*)
  }

  def removeAttribute(name: String) = {
    _attributes -= name
    log.debug(s"$ownId: removed attribute '$name'")
    notifyListActors(obj("name" -> name, "change" -> "remove"))
  }

  protected var _children = Map.empty[String, DSANode]

  def children = Future.successful(_children)

  def child(name: String): Future[Option[DSANode]] = children map (_.get(name))

  def addChild(name: String, profile: Option[String] = None, valueType: Option[DSAValueType] = None) = synchronized {
    val props = DSANode.props(Some(TypedActor.self))
    val child: DSANode = TypedActor(TypedActor.context).typedActorOf(props, name.forAkka)
    profile foreach {
      child.profile = _
    }
    valueType.foreach(child.valueType = _)
    addChild(name, child)
  }

  override def addChild(name: String, paramsAndConfigs: (String, DSAVal)*): Future[DSANode] = synchronized {
    val props = DSANode.props(Some(TypedActor.self))
    val child: DSANode = TypedActor(TypedActor.context).typedActorOf(props, name.forAkka)
    child.addConfigs(paramsAndConfigs.filter(_._1.startsWith("$")): _*)
    child.addAttributes(paramsAndConfigs.filter(_._1.startsWith("@")): _*)
    addChild(name, child)
  }

  override def addChild(name: String, child: DSANode): Future[DSANode] = {
    _children += name -> child
    log.debug(s"$ownId: added child '$name'")
    notifyListActors(array(name, obj("$is" -> "node")))
    Future.successful(child)
  }

  def removeChild(name: String) = {
    _children get (name) foreach TypedActor(TypedActor.context).stop
    _children -= name
    log.debug(s"$ownId: removed child '$name'")
    notifyListActors(obj("name" -> name, "change" -> "remove"))
  }

  private var _action: Option[DSAAction] = None

  def action = _action

  def action_=(a: DSAAction) = {
    _action = Some(a)
  }

  def invoke(params: DSAMap) = {
    implicit val system: ActorSystem = TypedActor.context.system
    _action map (_.handler(ActionContext(this, params)))
  }

  protected var _sids = Map.empty[Int, ActorRef]

  def subscribe(sid: Int, ref: ActorRef) = _sids += sid -> ref

  def unsubscribe(sid: Int) = _sids -= sid

  protected var _rids = Map.empty[Int, ActorRef]

  def list(rid: Int, ref: ActorRef) = _rids += rid -> ref

  def unlist(rid: Int) = _rids -= rid

  /**
    * Handles custom messages, that are not part of the Typed API.
    */
  def onReceive(message: Any, sender: ActorRef) = message match {

    case e @ RequestEnvelope(requests, _) =>
      log.debug(s"$ownId: received $e")
      val responses = requests flatMap handleRequest(sender)
      sender ! ResponseEnvelope(responses)

    case AppendDsId2Token(name, value) =>
      log.info(s"$ownId: received AppendDsId2Token ($name, $value)")
      if (name.startsWith("$")) {

        val oIds = _configs.get(name)
        val values: DSAVal = oIds match {
          case None      => array(value)
          case Some(arr) =>
            val srcVal = arr.asInstanceOf[ArrayValue].value.toSeq
            if (srcVal.contains(StringValue(value)))
              array(value)
            else
              srcVal ++ Seq(StringValue(value))
        }

        _configs ++= Seq(name -> values)
      } else
        log.warning("UpdateToken's parameter does not contains @ " + name)

    case GetTokens =>
      log.info(s"$ownId: GetTokens received")
      val fResponse = children.map { m =>
        m.values.filter(node => node.action.isEmpty).toList
      }
      fResponse pipeTo sender

    case GetToken(name) =>
      log.info(s"$ownId: GetToken($name) received")
      child(name) pipeTo sender

    case GetConfigVal(name) =>
      log.info(s"$ownId: GetDSLinksIds received")
      val fResp = config(name)
      fResp pipeTo sender

    case GetRules(path) =>
      log.info(s"$ownId: GetRules($path) received")

      def selectRule(rules: Map[String, DSANode]) = rules.filterKeys(path.startsWith).maxBy(_._1.length)._2

      val ruleMap = children flatMap { roles =>
        val namesAndRules = roles map {
          case (roleName, roleNode) =>
            val selectedRule = roleNode.children.map(selectRule)
            selectedRule map (rule => roleName -> rule)
        }
        Future.sequence(namesAndRules).map(_.toMap)
      }

      ruleMap pipeTo sender

    case msg @ _ =>
      log.error("Unknown message: " + msg)
  }

  // Map for storing [GroupName, PermissionOfClosestParent].
  // The 'PermissionOfClosestParent' is relative to current node path
  var permissionMap: Map[String, String] = Map.empty

  def initPermission() = {
    val rolesNodeActor = RootNodeActor.childProxy(Settings.Paths.Roles)(system)

    // Get list of Rules for corresponding path and it's parents
    val rules = rolesNodeActor ? GetRules(path)

    var permissionStorage = Map.empty

//    children foreach { childrenMap =>
//      childrenMap foreach { case (role, node) =>
//      }
//    }
//
//    rules foreach { rule =>
//      //      TODO:  Initializing Map of Rules for permission
//      permissionMap += ("a" -> rule)
//    }
  }

  def getGroupName(token: String): Option[String] = {
    val tokenId = Tokens.getTokenId(token)
    val tokenNodeActor = RootNodeActor.childProxy(Settings.Paths.Tokens)(system)
    val fTokensNode = (tokenNodeActor ? GetToken(tokenId)).mapTo[Option[DSANode]]

    val fRes = fTokensNode flatMap {
      case Some(node) =>
        node.child("role")
      case None       =>
        log.error("There is no node: " + Settings.Paths.Tokens + "/" + tokenId)
        Future.successful(None)
    }

    val fRes2 = fRes flatMap {
      case Some(node: DSANode) =>
        node.value map { item => Some(item.toString) }
      case None                =>
        log.error("There is no value node of token's node: " + Settings.Paths.Tokens + "/" + tokenId)
        Future.successful(None)
    }

    Await.result(fRes2, Duration.Inf)
  }

  def checkPermission(token: String, action: String) = {
    val groupName = getGroupName(token)

    groupName.fold(false) { name =>
      permissionMap.get(name) match {
        case Some(value) => action.equals(value) || value.equals("config") // TODO: Change "Config" to enum
        case None        => false
      }
    }
  }

  // event handlers

  def preStart() = log.info(s"DSANode[$path] initialized")

  def postStop() = log.info(s"DSANode[$path] stopped")
}



