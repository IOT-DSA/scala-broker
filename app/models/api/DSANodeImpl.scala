package models.api

import akka.actor.{ActorRef, TypedActor, TypedProps}
import akka.event.Logging
import models.{RequestEnvelope, ResponseEnvelope}
import models.api.DSAValueType.{DSADynamic, DSAValueType}
import models.rpc.DSAValue.{DSAMap, DSAVal, array, obj}
import models.rpc.{CloseRequest, DSARequest, DSAResponse, InvokeRequest, ListRequest, RemoveRequest, SetRequest, StreamState, SubscribeRequest, UnsubscribeRequest}
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global


/**
  * DSA Node actor-based implementation.
  */
class DSANodeImpl(val parent: Option[DSANode])
  extends DSANode with TypedActor.Receiver with TypedActor.PreStart with TypedActor.PostStop {

  protected val log = Logging(TypedActor.context.system, getClass)

  val name = TypedActor.context.self.path.name
  val path = parent.map(_.path).getOrElse("") + "/" + name

  protected def ownId = s"[$path]"

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

  private val _configs = collection.mutable.Map[String, DSAVal]("$is" -> "node")
  def configs = Future.successful(_configs.toMap)
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

  private val _attributes = collection.mutable.Map.empty[String, DSAVal]
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

  private val _children = collection.mutable.Map.empty[String, DSANode]
  def children = Future.successful(_children.toMap)
  def child(name: String) = children map (_.get(name))
  def addChild(name: String) = synchronized {
    val props = DSANode.props(Some(TypedActor.self))
    val child = TypedActor(TypedActor.context).typedActorOf(props, name)
    _children += name -> child
    log.debug(s"$ownId: added child '$name'")
    notifyListActors(array(name, obj("$is" -> "node")))
    Future.successful(child)
  }
  def removeChild(name: String) = {
    _children remove name foreach TypedActor(TypedActor.context).stop
    log.debug(s"$ownId: removed child '$name'")
    notifyListActors(obj("name" -> name, "change" -> "remove"))
  }

  private var _action: Option[DSAAction] = None
  def action = _action
  def action_=(a: DSAAction) = {
    _action = Some(a)
    _configs("$params") = a.params map {
      case (pName, pType) => obj("name" -> pName, "type" -> pType.toString)
    }
    _configs("$invokable") = "write"
    profile = "static"
  }

  def invoke(params: DSAMap) = _action foreach (_.handler(ActionContext(this, params)))

  private val _sids = collection.mutable.Map.empty[Int, ActorRef]
  def subscribe(sid: Int, ref: ActorRef) = _sids += sid -> ref
  def unsubscribe(sid: Int) = _sids -= sid

  private val _rids = collection.mutable.Map.empty[Int, ActorRef]
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

    case msg @ _ => log.error("Unknown message: " + msg)
  }

  /**
    * Handles DSA requests by processing them and sending the response to itself.
    */
  def handleRequest(sender: ActorRef): PartialFunction[DSARequest, Iterable[DSAResponse]] = {

    /* set */

    case SetRequest(rid, "", newValue, _) =>
      value = newValue
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    case SetRequest(rid, name, newValue, _) if name.startsWith("$") =>
      addConfigs(name -> newValue)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    case SetRequest(rid, name, newValue, _) if name.startsWith("@") =>
      addAttributes(name -> newValue)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    /* remove */

    case RemoveRequest(rid, name) if name.startsWith("$") =>
      removeConfig(name)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    case RemoveRequest(rid, name) if name.startsWith("@") =>
      removeAttribute(name)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    /* invoke */

    case InvokeRequest(rid, _, params, _) =>
      invoke(params)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    /* subscribe */

    case SubscribeRequest(rid, paths) =>
      assert(paths.size == 1, "Only a single path is allowed in Subscribe")
      subscribe(paths.head.sid, sender)
      val head = DSAResponse(rid, Some(StreamState.Closed))
      val tail = if (_value != null) {
        val update = obj("sid" -> paths.head.sid, "value" -> _value, "ts" -> now)
        DSAResponse(0, Some(StreamState.Open), Some(List(update))) :: Nil
      } else Nil
      head :: tail

    /* unsubscribe */

    case UnsubscribeRequest(rid, sids) =>
      assert(sids.size == 1, "Only a single sid is allowed in Unsubscribe")
      unsubscribe(sids.head)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    /* list */

    // TODO this needs to be rewritten to remove blocking
    case ListRequest(rid, _) =>
      list(rid, sender)
      val cfgUpdates = array("$is", _configs("$is")) +: toUpdateRows(_configs - "$is")
      val attrUpdates = toUpdateRows(_attributes)
      val childUpdates = Await.result(Future.sequence(_children map {
        case (name, node) => node.configs map (cfgs => name -> cfgs)
      }), Duration.Inf) map {
        case (name, cfgs) => array(name, cfgs)
      }
      val updates = cfgUpdates ++ attrUpdates ++ childUpdates
      DSAResponse(rid, Some(StreamState.Open), Some(updates.toList)) :: Nil

    /* close */

    case CloseRequest(rid) =>
      unlist(rid)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil
  }

  // event handlers

  def preStart() = log.info(s"DSANode[$path] initialized")

  def postStop() = log.info(s"DSANode[$path] stopped")

  /**
    * Sends DSAResponse instances to actors listening to SUBSCRIBE updates.
    */
  private def notifySubscribeActors(value: DSAVal) = {
    val ts = now
    _sids foreach {
      case (sid, ref) =>
        val update = obj("sid" -> sid, "value" -> value, "ts" -> ts)
        val response = ResponseEnvelope(DSAResponse(0, Some(StreamState.Open), Some(List(update))) :: Nil)
        ref ! response
    }
  }

  /**
    * Sends DSAResponse instances to actors listening to LIST updates.
    */
  private def notifyListActors(updates: DSAVal*) = _rids foreach {
    case (rid, ref) => ref ! ResponseEnvelope(DSAResponse(rid, Some(StreamState.Open), Some(updates.toList)) :: Nil)
  }

  /**
    * Formats the current date/time as ISO.
    */
  private def now = DateTime.now.toString(ISODateTimeFormat.dateTime)

  /**
    * Converts data to a collection of DSAResponse-compatible update rows.
    */
  private def toUpdateRows(data: collection.Map[String, DSAVal]) = data map (cfg => array(cfg._1, cfg._2)) toSeq
}


