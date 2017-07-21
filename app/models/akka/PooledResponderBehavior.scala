package models.akka

import scala.util.control.NonFatal

import akka.actor.{ ActorRef, actorRef2Scala, ActorDSL }
import akka.routing.{ Broadcast, ConsistentHashingPool, ConsistentHashingRouter }
import models._
import models.rpc._
import models.rpc.DSAMethod.DSAMethod
import models.rpc.DSAValue.DSAVal
import models.splitPath

/**
 * Encapsulates request information for lookups.
 */
case class LookupRecord(method: DSAMethod, targetId: Int, origin: Option[Origin], path: Option[String])

/**
 * Request registry tied to RID.
 */
class RidRegistry {
  private val nextTargetId = new IntCounter(1)

  private val callsByTargetId = collection.mutable.Map.empty[Int, LookupRecord]
  private val callsByOrigin = collection.mutable.Map.empty[Origin, LookupRecord]
  private val callsByPath = collection.mutable.Map.empty[String, LookupRecord]

  /**
   * Saves lookup record for LIST call.
   */
  def saveListLookup(path: String): Int = saveLookup(DSAMethod.List, None, Some(path))

  /**
   * Saves lookup record for Set, Remove or Invoke call.
   */
  def savePassthroughLookup(method: DSAMethod, origin: Origin): Int = saveLookup(method, Some(origin), None)

  /**
   * Saves lookup record for SUBSCRIBE call.
   */
  def saveSubscribeLookup(origin: Origin): Int = saveLookup(DSAMethod.Subscribe, Some(origin), None)

  /**
   * Saves lookup record for UNSUBSCRIBE call.
   */
  def saveUnsubscribeLookup(origin: Origin): Int = saveLookup(DSAMethod.Unsubscribe, Some(origin), None)

  /**
   * Saves the lookup and returns the newly generated target RID.
   */
  private def saveLookup(method: DSAMethod, origin: Option[Origin], path: Option[String]): Int = {
    val tgtId = nextTargetId.inc
    val record = LookupRecord(method, tgtId, origin, path)

    callsByTargetId(tgtId) = record
    origin foreach (callsByOrigin(_) = record)
    path foreach (callsByPath(_) = record)

    tgtId
  }

  /**
   * Locates the call record by target RID (used by response handlers).
   */
  def lookupByTargetId(targetId: Int): Option[LookupRecord] = callsByTargetId.get(targetId)

  /**
   * Locates the call record by the request origin (applicable to passthrough calls,
   * though in fact used only to close streaming INVOKE requests.)
   */
  def lookupByOrigin(origin: Origin): Option[LookupRecord] = callsByOrigin.get(origin)

  /**
   * Locates the call record by the path (applicable to LIST calls only).
   */
  def lookupByPath(path: String): Option[LookupRecord] = callsByPath.get(path)

  /**
   * Removes the call record.
   */
  def removeLookup(record: LookupRecord) = {
    record.origin foreach callsByOrigin.remove
    record.path foreach callsByPath.remove
    callsByTargetId -= record.targetId
  }

  /**
   * Returns brief diagnostic information for the registry.
   */
  def info = s"Origin Lookups: ${callsByOrigin.size}, Target Lookups: ${callsByTargetId.size}, Path Lookups: ${callsByPath.size}"
}

/**
 * Request registry tied to SID.
 */
class SidRegistry {
  private val targetSids = new IntCounter(1)

  private val pathBySid = collection.mutable.Map.empty[Int, String]
  private val sidByPath = collection.mutable.Map.empty[String, Int]

  /**
   * Saves the lookup and returns the newly generated SID.
   */
  def saveLookup(path: String): Int = {
    val tgtSid = targetSids.inc
    sidByPath(path) = tgtSid
    pathBySid(tgtSid) = path
    tgtSid
  }

  /**
   * Locates the SID by the path.
   */
  def lookupByPath(path: String): Option[Int] = sidByPath.get(path)

  /**
   * Removes the lookup.
   */
  def removeLookup(targetSid: Int) = {
    val path = pathBySid(targetSid)
    sidByPath -= path
    pathBySid -= targetSid
  }

  /**
   * Returns brief diagnostic information for the registry.
   */
  def info = s"Target Lookups: ${pathBySid.size}, Path Lookups: ${sidByPath.size}"
}

/**
 * Handles communication with a remote DSLink in Responder mode.
 */
trait PooledResponderBehavior { me: DSLinkActor =>
  import context.system
  import ResponderWorker._
  import ActorDSL._
  import ConsistentHashingRouter._

  type RequestHandler = PartialFunction[DSARequest, HandlerResult]
  type ResponseHandler = PartialFunction[DSAResponse, List[(ActorRef, DSAResponse)]]

  private val ridRegistry = new RidRegistry

  private val sidRegistry = new SidRegistry

  // stores responder's nodes' attributes locally
  private val attributes = collection.mutable.Map.empty[String, Map[String, DSAVal]]

  // LIST and SUBSCRIBE workers
  private val originHash: ConsistentHashMapping = {
    case AddOrigin(_, origin)   => origin
    case RemoveOrigin(origin)   => origin
    case LookupTargetId(origin) => origin
  }

  private val listPool = ConsistentHashingPool(Settings.Responder.ListPoolSize, hashMapping = originHash)
  private val listRouter = context.actorOf(listPool.props(ResponderListWorker.props(linkName)))

  private val subsPool = ConsistentHashingPool(Settings.Responder.SubscribePoolSize, hashMapping = originHash)
  private val subsRouter = context.actorOf(subsPool.props(ResponderSubscribeWorker.props(linkName)))

  /**
   * Processes incoming requests and responses.
   */
  val responderBehavior: Receive = {
    case env @ RequestEnvelope(requests) =>
      log.info(s"$ownId: received $env from $sender")
      val result = processRequests(requests)
      if (!result.requests.isEmpty)
        ws foreach (_ ! RequestEnvelope(result.requests))
      if (!result.responses.isEmpty)
        sender ! ResponseEnvelope(result.responses)

    case m @ ResponseMessage(_, _, responses) =>
      log.debug(s"$ownId: received $m")
      processResponses(responses) foreach {
        case (to, rsps) => to ! ResponseEnvelope(rsps)
      }
  }

  /**
   * Processes the requests and returns requests that need to be forwaded to their destinations
   * as well as the responses that need to be delivered to the originators.
   */
  private def processRequests(requests: Seq[DSARequest]): HandlerResult = {
    val handler = handleListRequest orElse handlePassthroughRequest orElse
      handleSubscribeRequest orElse handleUnsubscribeRequest orElse handleCloseRequest

    val results = requests map (request => try {
      handler(request)
    } catch {
      case NonFatal(e) => log.error(s"$ownId: error handling request $request - {}", e); HandlerResult.Empty
    })

    log.debug("RID after Req: " + ridRegistry.info)
    log.debug("SID after Req: " + sidRegistry.info)

    HandlerResult.flatten(results)
  }

  /**
   * Processes the responses and returns the translated ones groupped by their destinations.
   */
  def processResponses(responses: Seq[DSAResponse]): Map[ActorRef, Seq[DSAResponse]] = {
    val handler = handleSubscribeResponse orElse handleNonSubscribeResponse

    val results = responses flatMap handler

    log.debug("RID after Rsp: " + ridRegistry.info)
    log.debug("SID after Rsp: " + sidRegistry.info)

    results groupBy (_._1) mapValues (_.map(_._2))
  }

  /**
   * Handles List request.
   */
  private val handleListRequest: RequestHandler = {
    case ListRequest(rid, path) =>
      val origin = Origin(sender, rid)
      ridRegistry.lookupByPath(path) match {
        case None =>
          val tgtId = ridRegistry.saveListLookup(path)
          listRouter ! AddOrigin(tgtId, origin)
          HandlerResult(ListRequest(tgtId, translatePath(path)))
        case Some(rec) =>
          listRouter ! AddOrigin(rec.targetId, origin)
          HandlerResult.Empty
      }
  }

  /**
   * Translates the original request before sending it to responder link.
   */
  private def handlePassthroughRequest: RequestHandler = {

    def tgtId(srcId: Int, method: DSAMethod) = ridRegistry.savePassthroughLookup(method, Origin(sender, srcId))

    def saveAttribute(nodePath: String, name: String, value: DSAVal) = {
      log.info(s"$ownId: saving attribute under $nodePath: $name = $value")
      val attrMap = attributes.getOrElse(nodePath, Map.empty)
      attributes(nodePath) = attrMap + (name -> value)
    }

    {
      case SetRequest(rid, path, value, permit) if isAttribute(path) =>
        val (nodePath, attrName) = splitPath(path)
        saveAttribute(nodePath, attrName.get, value)
        HandlerResult(DSAResponse(rid, Some(StreamState.Closed)))

      case SetRequest(rid, path, value, permit) =>
        HandlerResult(SetRequest(tgtId(rid, DSAMethod.Set), translatePath(path), value, permit))

      case RemoveRequest(rid, path) =>
        HandlerResult(RemoveRequest(tgtId(rid, DSAMethod.Remove), translatePath(path)))

      case InvokeRequest(rid, path, params, permit) if path.endsWith("/" + AddAttributeAction) =>
        val attrName = params("name").value.toString
        val nodePath = path.dropRight(AddAttributeAction.size + 1)
        saveAttribute(nodePath, attrName, params("value"))
        HandlerResult(DSAResponse(rid, Some(StreamState.Closed)))

      case InvokeRequest(rid, path, params, permit) if path.endsWith("/" + SetValueAction) =>
        val attrPath = path.dropRight(SetValueAction.size + 1)
        val attrValue = params("value")
        HandlerResult(SetRequest(tgtId(rid, DSAMethod.Invoke), translatePath(attrPath), attrValue, permit))

      case InvokeRequest(rid, path, params, permit) =>
        HandlerResult(InvokeRequest(tgtId(rid, DSAMethod.Invoke), translatePath(path), params, permit))
    }
  }

  /**
   * Handles Subscribe request.
   */
  private val handleSubscribeRequest: RequestHandler = {
    case req @ SubscribeRequest(srcRid, _) =>
      val srcPath = req.path // to ensure there's only one path (see requester actor)
      val ridOrigin = Origin(sender, srcRid)
      val sidOrigin = Origin(sender, srcPath.sid)

      sidRegistry.lookupByPath(srcPath.path) match {
        case None =>
          val tgtRid = ridRegistry.saveSubscribeLookup(ridOrigin)
          val tgtSid = sidRegistry.saveLookup(srcPath.path)
          val tgtPath = srcPath.copy(path = translatePath(srcPath.path), sid = tgtSid)
          subsRouter ! AddOrigin(tgtSid, sidOrigin)
          HandlerResult(SubscribeRequest(tgtRid, tgtPath))
        case Some(tgtSid) =>
          // Close and Subscribe response may come out of order, leaving until it's a problem
          subsRouter ! AddOrigin(tgtSid, sidOrigin)
          HandlerResult(DSAResponse(srcRid, Some(StreamState.Closed)))
      }
  }

  /**
   * Handles Unsubscribe request.
   */
  private val handleUnsubscribeRequest: RequestHandler = {
    case req @ UnsubscribeRequest(rid, _) =>
      val ridOrigin = Origin(sender, rid)
      val sidOrigin = Origin(sender, req.sid)
      removeOrigin(sidOrigin, subsPool, subsRouter) map { targetSid =>
        sidRegistry.removeLookup(targetSid)
        val tgtRid = ridRegistry.saveUnsubscribeLookup(ridOrigin)
        HandlerResult(UnsubscribeRequest(tgtRid, List(targetSid)))
      } getOrElse HandlerResult(DSAResponse(rid, Some(StreamState.Closed)))
  }

  /**
   * Handles Close request.
   */
  private val handleCloseRequest: RequestHandler = {
    case CloseRequest(rid) =>
      val origin = Origin(sender, rid)
      ridRegistry.lookupByOrigin(origin) match {
        case Some(LookupRecord(_, tgtId, _, _)) => HandlerResult(CloseRequest(tgtId)) // passthrough call
        case _ => // LIST call
          removeOrigin(origin, listPool, listRouter) map { targetId =>
            ridRegistry.lookupByTargetId(targetId) foreach ridRegistry.removeLookup
            HandlerResult(CloseRequest(targetId))
          } getOrElse HandlerResult.Empty
      }
  }

  /**
   * Removes the origin from one of the workers. Returns `Some(targetId)` if the entry can be
   * removed (i.e. no listeners left), or None otherwise.
   */
  private def removeOrigin(origin: Origin, pool: ConsistentHashingPool, router: ActorRef) = {
    val ibox = inbox()
    router.tell(LookupTargetId(origin), ibox.getRef)
    ibox.receive().asInstanceOf[Option[Int]] map { targetId =>
      router ! RemoveOrigin(origin)
      router.tell(Broadcast(GetOriginCount(targetId)), ibox.getRef)
      val count = (1 to pool.nrOfInstances).map(_ => ibox.receive().asInstanceOf[Int]).sum
      (targetId, count < 1)
    } collect {
      case (targetId, true) => targetId
    }
  }

  /**
   * Forwards response to the subscriber router and returns nothing.
   */
  private def handleSubscribeResponse: ResponseHandler = {
    case rsp @ DSAResponse(0, _, _, _, _) => subsRouter ! Broadcast(rsp); Nil
  }

  /**
   * If the response is for LIST request, forwards it to the list router. Otherwise
   * translates the response's RID and returns to be sent to the requester.
   */
  private def handleNonSubscribeResponse: ResponseHandler = {
    case rsp if rsp.rid != 0 =>
      val result = ridRegistry.lookupByTargetId(rsp.rid) match {
        case Some(LookupRecord(DSAMethod.List, _, _, _)) =>
          listRouter ! Broadcast(rsp)
          Nil
        case Some(rec @ LookupRecord(_, _, Some(origin), _)) =>
          if (rsp.stream == Some(StreamState.Closed))
            ridRegistry.removeLookup(rec)
          List((origin.source, rsp.copy(rid = origin.sourceId)))
        case _ =>
          log.warning(s"$ownId: Cannot find original request for target RID: ${rsp.rid}")
          Nil
      }

      result
  }

  /**
   * Removes the linkPath prefix from the path.
   */
  private def translatePath(path: String) = {
    val chopped = path.drop(linkPath.size)
    if (!chopped.startsWith("/")) "/" + chopped else chopped
  }
}