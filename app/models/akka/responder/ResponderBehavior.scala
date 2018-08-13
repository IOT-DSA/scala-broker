package models.akka.responder

import scala.util.control.NonFatal
import akka.persistence.PersistentActor
import akka.actor._
import kamon.Kamon
import models._
import models.akka.{RequestsProcessed, ResponsesProcessed}
import models.metrics.Meter
import models.rpc._
import models.rpc.DSAMethod.DSAMethod
import models.rpc.DSAValue.{ArrayValue, DSAVal, MapValue, StringValue, array}

/**
 * Handles communication with a remote DSLink in Responder mode.
 */
trait ResponderBehavior extends Meter { me: PersistentActor with ActorLogging  =>
  import RidRegistry._

  protected def linkPath: String

  protected def ownId: String

  type RequestHandler = PartialFunction[DSARequest, HandlerResult]
  type ResponseHandler = PartialFunction[DSAResponse, List[(ActorRef, DSAResponse)]]

  // stores call records for forward and reverse RID lookup
  private val ridRegistry = new RidRegistry

  // stores call records for forward and reverse SID lookup (SUBSCRIBE/UNSUBSCRIBE only)
  private val sidRegistry = new SidRegistry

  // stores responder's nodes' attributes locally
  private val attributes = collection.mutable.Map.empty[String, Map[String, DSAVal]]

  // processes request using the appropriate handler
  private val requestHandler = handlePassthroughRequest orElse handleListRequest orElse
    handleSubscribeRequest orElse handleUnsubscribeRequest orElse handleCloseRequest

  /**
   * Processes incoming requests and responses.
   */
  val responderBehavior: Receive = {
    case pack @ InResponseEnvelope(messages) =>
      log.debug("{}: received pack {}", ownId, pack)
      messages foreach { m =>
          log.debug("{}: received {}", ownId, m)
          //      persist(ResponsesProcessed(responses)) { event =>
          processResponses(m.responses) foreach {
            case (to, rsps) => to ! OutResponseEnvelope(rsps)
          }
        //      }
      }
    case env @ OutRequestEnvelope(requests) =>
      log.debug("{}: received {} from {}", ownId, env, sender)
//      persist(RequestsProcessed(requests)) { event =>
      val result = processRequests(requests)
      if (!result.requests.isEmpty)
        sendToEndpoint(OutRequestEnvelope(result.requests))
      if (!result.responses.isEmpty)
        sender ! OutResponseEnvelope(result.responses)
//      }

    case m @ ResponseMessage(_, _, responses) =>
      log.debug("{}: received {}", ownId, m)
//      persist(ResponsesProcessed(responses)) { event =>
      processResponses(responses) foreach {
        case (to, rsps) => to ! OutResponseEnvelope(rsps)
      }
//      }
  }

  /**
    * Recovers events of responder behavior from the journal.
    */
  val responderRecover: Receive = {
    case event: RequestsProcessed =>
      log.info("{}: trying to recover {}", ownId, event)
      // TODO has to be improved to separate the functionality
      processRequests(event.requests)
    case event: ResponsesProcessed =>
      log.info("{}: trying to recover {}", ownId, event)
      // TODO has to be improved to separate the functionality
      processResponses(event.responses)
  }

  /**
   * Processes the requests and returns requests that need to be forwaded to their destinations
   * as well as the responses that need to be delivered to the originators.
   */
  private def processRequests(requests: Seq[DSARequest]): HandlerResult = {

    val results = requests map (request => try {
      requestHandler(request)
    } catch {
      case NonFatal(e) => log.error("{}: error handling request {} - {}", ownId, request, e); HandlerResult.Empty
    })

    log.debug("{}: RID after Req: {}", ownId, ridRegistry.info)
    log.debug("{}: SID after Req: {}", ownId, sidRegistry.info)

    HandlerResult.flatten(results)
  }

  /**
   * Processes the responses and returns the translated ones groupped by their destinations.
   */
  def processResponses(responses: Seq[DSAResponse]): Map[ActorRef, Seq[DSAResponse]] = {
    val handler = handleSubscribeResponse orElse handleNonSubscribeResponse

    val results = responses flatMap handler

    log.debug("{}: RID after Rsp: {}", ownId, ridRegistry.info)
    log.debug("{}: SID after Rsp: {}", ownId, sidRegistry.info)
    results groupBy (_._1) mapValues (_.map(_._2))
  }

  /**
   * Handles List request.
   */
  private def handleListRequest: RequestHandler = {
    case ListRequest(rid, path) =>
      val origin = Origin(sender, rid)
      ridRegistry.lookupByPath(path) match {
        case None =>
          val tgtId = ridRegistry.saveListLookup(path)
          addListOrigin(tgtId, origin)
          HandlerResult(ListRequest(tgtId, translatePath(path)))
        case Some(rec) =>
          addListOrigin(rec.targetId, origin)
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
  private def handleSubscribeRequest: RequestHandler = {
    case req @ SubscribeRequest(srcRid, _) =>
      val srcPath = req.path // to ensure there's only one path (see requester actor)
      val ridOrigin = Origin(sender, srcRid)
      val sidOrigin = Origin(sender, srcPath.sid)

      Kamon.currentSpan().tag("rid", srcRid)
      Kamon.currentSpan().tag("kind", "SubscribeRequest")

      sidRegistry.lookupByPath(srcPath.path) match {
        case None =>
          val tgtRid = ridRegistry.saveSubscribeLookup(ridOrigin)
          val tgtSid = sidRegistry.saveLookup(srcPath.path)
          Kamon.currentSpan().tag("sid", tgtSid)
          val tgtPath = srcPath.copy(path = translatePath(srcPath.path), sid = tgtSid)
          addSubscribeOrigin(tgtSid, sidOrigin)
          HandlerResult(SubscribeRequest(tgtRid, tgtPath))
        case Some(tgtSid) =>
          // Close and Subscribe response may come out of order, leaving until it's a problem
          addSubscribeOrigin(tgtSid, sidOrigin)
          Kamon.currentSpan().tag("sid", tgtSid)
          HandlerResult(DSAResponse(srcRid, Some(StreamState.Closed)))
      }
  }

  /**
   * Handles Unsubscribe request.
   */
  private def handleUnsubscribeRequest: RequestHandler = {
    case req @ UnsubscribeRequest(rid, _) =>
      val ridOrigin = Origin(sender, rid)
      val sidOrigin = Origin(sender, req.sid)
      removeSubscribeOrigin(sidOrigin) map { targetSid =>
        sidRegistry.removeLookup(targetSid)
        val tgtRid = ridRegistry.saveUnsubscribeLookup(ridOrigin)
        HandlerResult(UnsubscribeRequest(tgtRid, List(targetSid)))
      } getOrElse HandlerResult(DSAResponse(rid, Some(StreamState.Closed)))
  }

  /**
   * Handles Close request.
   */
  private def handleCloseRequest: RequestHandler = {
    case CloseRequest(rid) =>
      val origin = Origin(sender, rid)
      ridRegistry.lookupByOrigin(origin) match {
        case Some(LookupRecord(_, tgtId, _, _)) => HandlerResult(CloseRequest(tgtId)) // passthrough call
        case _ => // LIST call
          removeListOrigin(origin) map { targetId =>
            ridRegistry.lookupByTargetId(targetId) foreach ridRegistry.removeLookup
            HandlerResult(CloseRequest(targetId))
          } getOrElse HandlerResult.Empty
      }
  }

  /**
   * Forwards response to the recipients and returns nothing.
   */
  private def handleSubscribeResponse: ResponseHandler = {
    case rsp @ DSAResponse(0, _, _, _, _) =>
      deliverSubscribeResponse(rsp)
      countTagsNTimes("subscribe.response.in")(rsp.updates.map(_.size).getOrElse(0))
      Nil
  }

  /**
   * If the response is for LIST request, forwards it to the list router. Otherwise
   * translates the response's RID and returns to be sent to the requester.
   */
  private def handleNonSubscribeResponse: ResponseHandler = {
    case rsp if rsp.rid != 0 =>
      val result = ridRegistry.lookupByTargetId(rsp.rid) match {
        case Some(LookupRecord(DSAMethod.List, _, _, Some(path))) =>
          // add stored attributes for this path
          val attrUpdates = attributes.getOrElse(path, Map.empty) map {
            case (name, value) => array(name, value)
          }
          // adjust $base
          val oldUpdates = rsp.updates getOrElse Nil map (adjustBase.applyOrElse(_, identity[DSAVal]))
          val newResponse = rsp.copy(updates = Some(oldUpdates ++ attrUpdates))
          deliverListResponse(newResponse)
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

  /**
   * Extracts $base config from an update row.
   */
  val adjustBase: PartialFunction[DSAVal, DSAVal] = {
    case v: ArrayValue if v.value.headOption == Some(StringValue("$base")) =>
      array("$base", linkPath + v.value.tail.head.toString)
    case v: MapValue if v.value.contains("$base") =>
      MapValue(v.value + ("$base" -> (linkPath + v.value("$base").toString)))
  }

  /**
   * Adds the origin to the list of recipients for the given target RID.
   */
  protected def addListOrigin(targetId: Int, origin: Origin): Unit

  /**
   * Adds the origin to the list of recipients for the given target SID.
   */
  protected def addSubscribeOrigin(targetId: Int, origin: Origin): Unit

  /**
   * Removes the origin from the collection of LIST recipients it belongs to. Returns `Some(targetId)`
   * if the call record can be removed (i.e. no listeners left), or None otherwise.
   */
  protected def removeListOrigin(origin: Origin): Option[Int]

  /**
   * Removes the origin from the collection of SUBSCRIBE recipients it belongs to. Returns `Some(targetId)`
   * if the call record can be removed (i.e. no listeners left), or None otherwise.
   */
  protected def removeSubscribeOrigin(origin: Origin): Option[Int]

  /**
   * Delivers a LIST response to its recipients.
   */
  protected def deliverListResponse(rsp: DSAResponse): Unit

  /**
   * Delivers a SUBSCRIBE response to its recipients.
   */
  protected def deliverSubscribeResponse(rsp: DSAResponse): Unit

  /**
   * Sends a message to the endpoint, if connected.
   */
  protected def sendToEndpoint(msg: Any): Unit
}