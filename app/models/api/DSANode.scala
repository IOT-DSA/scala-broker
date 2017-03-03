package models.api

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

import akka.actor.{ ActorRef, TypedActor, TypedProps }
import akka.event.Logging
import models.{ MessageRouter, RequestEnvelope }
import models.actors.RPCProcessor
import models.rpc._
import models.rpc.DSAValue._
import play.api.cache.CacheApi

/**
 * A structural unit in Node API.
 */
trait DSANode {
  def parent: Option[DSANode]
  def name: String
  def path: String

  def value: Future[DSAVal]
  def value_=(v: DSAVal): Unit

  def displayName: Future[String]
  def displayName_=(name: String): Unit

  def profile: String
  def profile_=(p: String): Unit

  def configs: Future[Map[String, DSAVal]]
  def config(name: String): Future[Option[DSAVal]]
  def addConfigs(cfg: (String, DSAVal)*): Unit
  def removeConfig(name: String): Unit

  def attributes: Future[Map[String, DSAVal]]
  def attribute(name: String): Future[Option[DSAVal]]
  def addAttributes(cfg: (String, DSAVal)*): Unit
  def removeAttribute(name: String): Unit

  def children: Future[Map[String, DSANode]]
  def child(name: String): Future[Option[DSANode]]
  def addChild(name: String): Future[DSANode]
  def removeChild(name: String): Unit

  def action: Option[DSAAction]
  def action_=(a: DSAAction): Unit

  def invoke(params: DSAMap): Unit

  def subscribe(sid: Int, ref: ActorRef): Unit
  def unsubscribe(sid: Int): Unit

  def list(rid: Int, ref: ActorRef): Unit
  def unlist(rid: Int): Unit
}

/**
 * DSA Node actor-based implementation.
 */
class DSANodeImpl(router: MessageRouter, cache: CacheApi, val parent: Option[DSANode])
    extends DSANode with RPCProcessor
    with TypedActor.Receiver with TypedActor.PreStart with TypedActor.PostStop {

  protected val log = Logging(TypedActor.context.system, getClass)

  val name = TypedActor.context.self.path.name
  val path = parent.map(_.path).getOrElse("") + "/" + name

  cache.set(path, TypedActor.context.self)

  protected def ownPath = path
  protected def ownId = s"[$ownPath]"

  private var _value: DSAVal = _
  def value = Future.successful(_value)
  def value_=(v: DSAVal) = {
    _value = v
    log.debug(s"$ownId: changed value to $v")
    notifySubscribeActors(v)
  }

  private var _displayName: String = name
  def displayName = Future.successful(_displayName)
  def displayName_=(name: String) = _displayName = name

  private var _profile: String = "node"
  def profile = _profile
  def profile_=(p: String) = _profile = p

  private val _configs = collection.mutable.Map.empty[String, DSAVal]
  def configs = Future.successful(_configs.toMap)
  def config(name: String) = configs map (_.get(name))
  def addConfigs(cfg: (String, DSAVal)*) = {
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
  def addAttributes(attrs: (String, DSAVal)*) = {
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
  def addChild(name: String) = {
    val props = DSANode.props(router, cache, Some(this))
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
  def action_=(a: DSAAction) = _action = Some(a)

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
    case e @ RequestEnvelope(from, _, false, requests) =>
      log.debug(s"$ownId: received unconfirmed $e")
      val result = processRequests(from, requests)
      result.requests foreach handleRequest
      router.routeHandledResponses(path, from, result.responses: _*) recover {
        case NonFatal(e) => log.error(s"$ownId: error routing the response {}", e)
      }

    case e @ RequestEnvelope(_, _, true, requests) =>
      log.info(s"$ownId: received confirmed $e")
      requests foreach handleRequest

    case e @ DSAResponse(rid, stream, updates, columns, error) =>
      log.info(s"$ownId: received $e")
      if (router.delegateResponseHandling)
        router.routeUnhandledResponses(path, e)
      else processResponses(List(e)) foreach {
        case (to, rsps) => router.routeHandledResponses(path, to, rsps.toSeq: _*) recover {
          case NonFatal(e) => log.error(s"$ownId: error routing the responses {}", e)
        }
      }

    case msg @ _ => log.error("Unknown message: " + msg)
  }

  /**
   * Handles DSA requests by processing them and sending the response to itself.
   */
  def handleRequest: PartialFunction[DSARequest, Unit] = {

    /* set */

    case SetRequest(rid, "", newValue, _) =>
      value = newValue
      TypedActor.context.self ! DSAResponse(rid, Some(StreamState.Closed))

    case SetRequest(rid, name, newValue, _) if name.startsWith("$") =>
      addConfigs(name -> newValue)
      TypedActor.context.self ! DSAResponse(rid, Some(StreamState.Closed))

    case SetRequest(rid, name, newValue, _) if name.startsWith("@") =>
      addAttributes(name -> newValue)
      TypedActor.context.self ! DSAResponse(rid, Some(StreamState.Closed))

    /* remove */

    case RemoveRequest(rid, name) if name.startsWith("$") =>
      removeConfig(name)
      TypedActor.context.self ! DSAResponse(rid, Some(StreamState.Closed))

    case RemoveRequest(rid, name) if name.startsWith("@") =>
      removeAttribute(name)
      TypedActor.context.self ! DSAResponse(rid, Some(StreamState.Closed))

    /* invoke */

    case InvokeRequest(rid, _, params, _) =>
      invoke(params)
      TypedActor.context.self ! DSAResponse(rid, Some(StreamState.Closed))

    /* subscribe */

    case SubscribeRequest(rid, paths) =>
      assert(paths.size == 1, "Only a single path is allowed in Subscribe")
      subscribe(paths.head.sid, TypedActor.context.self)
      TypedActor.context.self ! DSAResponse(rid, Some(StreamState.Closed))
      if (_value != null) {
        val update = obj("sid" -> paths.head.sid, "value" -> _value, "ts" -> now)
        val response = DSAResponse(0, Some(StreamState.Open), Some(List(update)))
        TypedActor.context.self ! response
      }

    /* unsubscribe */

    case UnsubscribeRequest(rid, sids) =>
      assert(sids.size == 1, "Only a single sid is allowed in Unsubscribe")
      unsubscribe(sids.head)
      TypedActor.context.self ! DSAResponse(rid, Some(StreamState.Closed))

    /* list */

    case ListRequest(rid, _) =>
      val cfgUpdates = _configs map (cfg => array(cfg._1, cfg._2))
      val attrUpdates = _attributes map (attr => array(attr._1, attr._2))
      val childUpdates = _children map (c => array(c._1, obj("$is" -> c._2.profile)))
      val updates = Nil ++ cfgUpdates ++ attrUpdates ++ childUpdates
      TypedActor.context.self ! DSAResponse(rid, Some(StreamState.Open), Some(updates))

    /* close */

    case CloseRequest(rid) =>
      unlist(rid)
      TypedActor.context.self ! DSAResponse(rid, Some(StreamState.Closed))
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
        val response = DSAResponse(0, Some(StreamState.Open), Some(List(update)))
        ref ! response
    }
  }

  /**
   * Sends DSAResponse instances to actors listening to LIST updates.
   */
  private def notifyListActors(updates: DSAVal*) = _rids foreach {
    case (rid, ref) => ref ! DSAResponse(rid, Some(StreamState.Open), Some(updates.toList))
  }

  /**
   * Formats the current date/time as ISO.
   */
  private def now = DateTime.now.toString(ISODateTimeFormat.dateTime)
}

/**
 * Factory for DSANodeImpl instances.
 */
object DSANode {
  def props(router: MessageRouter, cache: CacheApi, parent: Option[DSANode]) =
    TypedProps(classOf[DSANode], new DSANodeImpl(router, cache, parent))
}