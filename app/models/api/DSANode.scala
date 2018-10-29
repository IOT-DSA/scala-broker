package models.api

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import DSAValueType.DSAValueType
import akka.actor.{ActorRef, TypedProps}
import models.ResponseEnvelope
import models.rpc._
import models.rpc.DSAValue.{DSAMap, DSAVal, StringValue, array, obj}
import models.util.LoggingAdapterInside
import models.util.DsaToAkkaCoder._

/**
  * A structural unit in Node API.
  */
trait DSANode {
  def parent: Option[DSANode]

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

  def children: Future[Map[String, DSANode]]

  def child(name: String): Future[Option[DSANode]]

  def addChild(name: String, profile: Option[String] = None, valueType: Option[DSAValueType] = None): Future[DSANode]

  def addChild(name: String, paramsAndConfigs: (String, DSAVal)*): Future[DSANode]

  def addChild(name: String, node: DSANode): Future[DSANode]

  def removeChild(name: String): Unit

  def action: Option[DSAAction]

  def action_=(a: DSAAction): Unit

  def invoke(params: DSAMap): Any

  def subscribe(sid: Int, ref: ActorRef): Unit

  def unsubscribe(sid: Int): Unit

  def list(rid: Int, ref: ActorRef): Unit

  def unlist(rid: Int): Unit
}

/**
  * Factory for [[InMemoryDSANode]] instances.
  */
object DSANode {
  /**
    * Creates a new [[InMemoryDSANode]] props instance.
    */
  def props(parent: Option[DSANode]) = TypedProps(classOf[DSANode], new InMemoryDSANode(parent))
}


trait DSANodeSubscriptions {
  self: DSANode with LoggingAdapterInside =>

  protected var _sids: Map[Int, ActorRef]
  protected var _rids: Map[Int, ActorRef]


  /**
    * Sends DSAResponse instances to actors listening to SUBSCRIBE updates.
    */
  def notifySubscribeActors(value: DSAVal) = {
    val ts = now
    _sids foreach {
      case (sid, ref) =>
        val update = obj("sid" -> sid, "value" -> value, "ts" -> ts)
        val response = ResponseEnvelope(DSAResponse(0, Some(StreamState.Open), Some(List(update))) :: Nil)
        ref ! response
        log.debug("notify subscription updates:\\{} \\to: \\{} -> {}", value, sid, ref)
    }
  }

  /**
    * Sends DSAResponse instances to actors listening to LIST updates.
    */
  def notifyListActors(updates: DSAVal*) = _rids foreach {
    case (rid, ref) =>
      ref ! ResponseEnvelope(DSAResponse(rid, Some(StreamState.Open), Some(updates.toList)) :: Nil)
      log.debug("notify list updates:\\{} \\to: \\{} -> {}", updates, rid, ref)
  }

  /**
    * Formats the current date/time as ISO.
    */
  def now = DateTime.now.toString(ISODateTimeFormat.dateTime)

}

trait DSANodeRequestHandler {
  self: DSANode =>

  protected def _configs: Map[String, DSAVal]

  protected def _attributes: Map[String, DSAVal]

  protected def _children: Map[String, DSANode]

  implicit val executionContext: ExecutionContext


  /**
    * Handles DSA requests by processing them and sending the response to itself.
    */
  def handleRequest(sender: ActorRef): PartialFunction[DSARequest, Iterable[DSAResponse]] = {

    /* set */

    case SetRequest(rid, p, newValue, _) =>
      val name = p.split("/").last

      if (name.startsWith("$")) {
        self.addConfigs(name -> newValue)
      } else if (name.startsWith("@")) {
        addAttributes(name -> newValue)
      } else {
        self.value = newValue
      }

      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    /* remove */

    case RemoveRequest(rid, p) =>
      val name = p.split("/").last

      if (name.startsWith("$")) {
        self.removeConfig(name)
      } else if (name.startsWith("@")) {
        removeAttribute(name)
      }

      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    /* invoke */

    case InvokeRequest(rid, _, params, _) =>
      val invokeRes = invoke(params)

      invokeRes match {
        case Some((tokenName, token)) =>
          val strTokenName = tokenName.asInstanceOf[String]
          val strToken = token.asInstanceOf[String]
          DSAResponse(rid, Some(StreamState.Closed), Some(List(array(strTokenName, strToken))),
            Some(List(ColumnInfo("TokenName", "String"), ColumnInfo("Token", "String")))) :: Nil
        case None                     =>
          DSAResponse(rid, Some(StreamState.Open)) :: Nil
        case _                        =>
          DSAResponse(rid, Some(StreamState.Open)) :: Nil
      }

    /* subscribe */

    case SubscribeRequest(rid, paths) =>
      assert(paths.size == 1, "Only a single path is allowed in Subscribe")
      subscribe(paths.head.sid, sender)
      val head = DSAResponse(rid, Some(StreamState.Closed))

      val futureTail = value.map { v =>
        if (v != null) {
          val update = obj("sid" -> paths.head.sid, "value" -> v, "ts" -> DateTime.now.toString(ISODateTimeFormat.dateTime)
          )
          DSAResponse(0, Some(StreamState.Open), Some(List(update))) :: Nil
        } else Nil

      }

      head :: Await.result(futureTail, Duration.Inf)

    /* unsubscribe */

    case UnsubscribeRequest(rid, sids) =>
      assert(sids.size == 1, "Only a single sid is allowed in Unsubscribe")
      unsubscribe(sids.head)
      DSAResponse(rid, Some(StreamState.Closed)) :: Nil

    /* list */

    // TODO this needs to be rewritten to remove blocking
    case ListRequest(rid, _) =>
      list(rid, sender)
      val cfgUpdates = array("$is", _configs.getOrElse("$is", StringValue(""))) +: toUpdateRows(_configs - "$is")
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

  /**
    * Converts data to a collection of DSAResponse-compatible update rows.
    */
  def toUpdateRows(data: collection.Map[String, DSAVal]) = data map (cfg => array(cfg._1, cfg._2)) toSeq
}
