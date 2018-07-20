package models.api

import akka.actor.{ActorRef, ActorSystem, TypedActor, TypedProps}
import akka.event.Logging
import models.akka.Messages.{AppendDsId2Token, GetConfigVal, GetTokens}
import models.{RequestEnvelope, ResponseEnvelope}
import models.api.DSAValueType.{DSADynamic, DSAValueType}
import models.rpc.DSAValue.{ArrayValue, DSAMap, DSAVal, StringValue, array, obj}
import models.util.LoggingAdapterInside

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import models.util.DsaToAkkaCoder._


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

  override implicit val executionContext:ExecutionContext = TypedActor.context.dispatcher
  val system = TypedActor.context.system

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

  def addChild(name: String, profile:Option[String] = None, valueType:Option[DSAValueType] = None) = synchronized {
    val props = DSANode.props(Some(TypedActor.self))
    val child:DSANode = TypedActor(TypedActor.context).typedActorOf(props, name.forAkka)
    profile foreach{child.profile = _}
    valueType.foreach(child.valueType = _)
    addChild(name, child)
  }

  override def addChild(name: String, paramsAndConfigs: (String, DSAVal)*): Future[DSANode] = {
    val props = DSANode.props(Some(TypedActor.self))
    val child:DSANode = TypedActor(TypedActor.context).typedActorOf(props, name.forAkka)
    child.addConfigs(paramsAndConfigs.filter(_._1.startsWith("$")):_*)
    child.addAttributes(paramsAndConfigs.filter(_._1.startsWith("@")):_*)
    addChild(name, child)
  }

  override def addChild(name: String, child:DSANode):Future[DSANode] = {
    _children += name -> child
    log.debug(s"$ownId: added child '$name'")
    notifyListActors(array(name, obj("$is" -> "node")))
    Future.successful(child)
  }

  def removeChild(name: String) = {
    _children get(name) foreach TypedActor(TypedActor.context).stop
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

    case e @ RequestEnvelope(requests) =>
      log.info(s"$ownId: received $e")
      val responses = requests flatMap handleRequest(sender)
      sender ! ResponseEnvelope(responses)
    case AppendDsId2Token(name, value) =>
      log.info(s"$ownId: received AppendDsId2Token ($name, $value)")
      if(name.startsWith("$")) {

        val oIds = _configs.get(name)
        val values: DSAVal = oIds match {
          case None => array(value)
          case Some(arr)  =>
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

      val response = Await.result(fResponse, Duration.Inf)
      sender ! response
    case GetConfigVal(name) =>
      log.info(s"$ownId: GetDSLinksIds received")
      val fResp = config(name)
      val response = Await.result(fResp, Duration.Inf)
      sender ! response
    case msg @ _ =>
      log.error("Unknown message: " + msg)
  }


  // event handlers

  def preStart() = log.info(s"DSANode[$path] initialized")

  def postStop() = log.info(s"DSANode[$path] stopped")
}



