package models.api

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import DSAValueType.{DSADynamic, DSAValueType}
import akka.event.Logging
import models.{RequestEnvelope, ResponseEnvelope}
import models.rpc._
import models.rpc.DSAValue.{DSAMap, DSAVal, StringValue, array, obj}
import akka.actor.typed.scaladsl._
import akka.actor.typed.{ActorRef, Behavior}

/**
 * A structural unit in Node API.
 */
trait DSANode {
  def parent: Option[ActorRef[RequestEnvelope]]
  def name: String
  def path: String

  def value: Future[DSAVal]
  def value_=(v: DSAVal): Unit

  def valueType: Future[DSAValueType]
  def valueType_=(vt: DSAValueType): Unit

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

  def children: Future[Map[String, ActorRef[RequestEnvelope]]]
  def child(name: String): Future[Option[ActorRef[RequestEnvelope]]]
  def addChild(name: String): Future[ActorRef[RequestEnvelope]]
  def removeChild(name: String): Unit

  def action: Option[DSAAction]
  def action_=(a: DSAAction): Unit

  def invoke(params: DSAMap): Unit

  def subscribe(sid: Int, ref: ActorRef[ResponseEnvelope]): Unit
  def unsubscribe(sid: Int): Unit

  def list(rid: Int, ref: ActorRef[ResponseEnvelope]): Unit
  def unlist(rid: Int): Unit
}

/**
 * DSA Node actor-based implementation.
 */
class DSANodeImpl(val parent: Option[ActorRef[RequestEnvelope]], val context: akka.actor.ActorContext)
    extends DSANode {
  import akka.actor.typed.scaladsl.adapter._

  protected val log = Logging(context.system, getClass)
  val name = context.self.path.name
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

  private val _children = collection.mutable.Map.empty[String, ActorRef[RequestEnvelope]]
  def children = Future.successful(_children.toMap)
  def child(name: String) = children map (_.get(name))
  def addChild(name: String) = synchronized {
    val child: ActorRef[RequestEnvelope] = context.spawn(new NodeActorBehavior(this).get(context.self), name)
    _children += name -> child
    log.debug(s"$ownId: added child '$name'")
    notifyListActors(array(name, obj("$is" -> "node")))
    Future.successful(child)
  }
  def removeChild(name: String) = {
    _children remove name foreach context.stop
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

  private val _sids = collection.mutable.Map.empty[Int, ActorRef[ResponseEnvelope]]
  def subscribe(sid: Int, ref: ActorRef[ResponseEnvelope]) = _sids += sid -> ref
  def unsubscribe(sid: Int) = _sids -= sid

  private val _rids = collection.mutable.Map.empty[Int, ActorRef[ResponseEnvelope]]
  def list(rid: Int, ref: ActorRef[ResponseEnvelope]) = _rids += rid -> ref
  def unlist(rid: Int) = _rids -= rid

  /**
   * Sends DSAResponse instances to actors listening to SUBSCRIBE updates.
   */
  private def notifySubscribeActors(value: DSAVal) = {
    val ts = Util.now
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
}

class NodeActorBehavior(dsaData: DSANode) {

  def get(sender: ActorRef[ResponseEnvelope]): Behavior[RequestEnvelope] =
    Behaviors.immutable[RequestEnvelope] { (context, message) =>
      message match {
        case e @ RequestEnvelope(requests) =>
          context.log.info(s"[${dsaData.path}]: received $e")
          val responses = requests flatMap handleRequest(sender)
          sender ! ResponseEnvelope(responses)
          Behavior.same
      }
    }

  /**
    * Handles DSA requests by processing them and sending the response to itself.
    */
  private def handleRequest(sender: ActorRef[ResponseEnvelope]): PartialFunction[DSARequest, Iterable[DSAResponse]] = {

    /* set */

    case SetRequest(rid, "", newValue, _) =>
      dsaData.value = newValue
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    case SetRequest(rid, name, newValue, _) if name.startsWith("$") =>
      dsaData.addConfigs(name -> newValue)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    case SetRequest(rid, name, newValue, _) if name.startsWith("@") =>
      dsaData.addAttributes(name -> newValue)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    /* remove */

    case RemoveRequest(rid, name) if name.startsWith("$") =>
      dsaData.removeConfig(name)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    case RemoveRequest(rid, name) if name.startsWith("@") =>
      dsaData.removeAttribute(name)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    /* invoke */

    case InvokeRequest(rid, _, params, _) =>
      dsaData.invoke(params)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    /* subscribe */

    case SubscribeRequest(rid, paths) =>
      assert(paths.size == 1, "Only a single path is allowed in Subscribe")
      dsaData.subscribe(paths.head.sid, sender)
      val head = DSAResponse(rid, Some(StreamState.Closed))
      val tail = if (dsaData.value != null) {
        val update = obj("sid" -> paths.head.sid, "value" -> dsaData.value, "ts" -> Util.now)
        DSAResponse(0, Some(StreamState.Open), Some(List(update))) :: Nil
      } else Nil
      head :: tail

    /* unsubscribe */

    case UnsubscribeRequest(rid, sids) =>
      assert(sids.size == 1, "Only a single sid is allowed in Unsubscribe")
      dsaData.unsubscribe(sids.head)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    /* list */

    // TODO this needs to be rewritten to remove blocking
    case ListRequest(rid, _) =>
      dsaData.list(rid, sender)
      // Probably should be moved to DSANodeImpl
      val cfgUpdates = array("$is", _configs("$is")) +: Util.toUpdateRows(_configs - "$is")
      val attrUpdates = Util.toUpdateRows(_attributes)
      val childUpdates = Await.result(Future.sequence(_children map {
        case (name, node) => node.configs map (cfgs => name -> cfgs)
      }), Duration.Inf) map {
        case (name, cfgs) => array(name, cfgs)
      }
      val updates = cfgUpdates ++ attrUpdates ++ childUpdates
      DSAResponse(rid, Some(StreamState.Open), Some(updates.toList)) :: Nil

    /* close */

    case CloseRequest(rid) =>
      dsaData.unlist(rid)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil
  }
}

object Util {

  /**
    * Formats the current date/time as ISO.
    */
  def now = DateTime.now.toString(ISODateTimeFormat.dateTime)

  /**
    * Converts data to a collection of DSAResponse-compatible update rows.
    */
  def toUpdateRows(data: collection.Map[String, DSAVal]) = data map (cfg => array(cfg._1, cfg._2)) toSeq
}