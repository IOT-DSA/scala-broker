package models.actors

import scala.util.Try
import scala.util.control.NonFatal

import akka.actor.{ ActorRef, actorRef2Scala }
import models.rpc._
import models.RequestEnvelope
import models.ResponseEnvelope

/**
 * Handles communication with a remote DSLink in Responder mode.
 */
trait ResponderBehavior { this: AbstractWebSocketActor =>
  import DSAValue._

  // lookup registries for SID (Subscribe/Unsubscribe) and RID (all other) requests
  private val ridRegistry = new CallRegistry(1)
  private val sidRegistry = new CallRegistry(1)

  /**
   * Processes incoming messages from Responder DSLink and dispatches requests to it.
   */
  val responderBehavior: Receive = {
    case e @ RequestEnvelope(from, to, requests) =>
      log.debug(s"$ownId: received $e")
      requests foreach handleRequest
    case m @ ResponseMessage(msg, ack, responses) =>
      log.info(s"$ownId: received $m from WebSocket")
      sendAck(msg)
      routeResponses(responses)
  }

  /**
   * Handles List request.
   */
  private val handleListRequest: PartialFunction[DSARequest, Unit] = {
    case ListRequest(rid, path) =>
      val origin = Origin(sender, rid)
      ridRegistry.lookupByPath(path) match {
        case None =>
          val targetRid = ridRegistry.saveLookup(origin, Some(path), None)
          sendRequest(ListRequest(targetRid, translatePath(path)))
        case Some(rec) =>
          ridRegistry.addOrigin(origin, rec)
          rec.lastResponse foreach { rsp =>
            val envelope = ResponseEnvelope(connInfo.linkPath, null, List(rsp.copy(rid = origin.sourceId)))
            route(envelope, origin.source)
          }
      }
  }

  /**
   * Handles Set, Remove and Invoke requests.
   */
  private val handlePassthroughRequest: PartialFunction[DSARequest, Unit] = {
    case SetRequest(rid, path, value, permit) =>
      val targetRid = ridRegistry.saveLookup(Origin(sender, rid), None, None)
      sendRequest(SetRequest(targetRid, translatePath(path), value, permit))
    case RemoveRequest(rid, path) =>
      val targetRid = ridRegistry.saveLookup(Origin(sender, rid), None, None)
      sendRequest(RemoveRequest(targetRid, translatePath(path)))
    case InvokeRequest(rid, path, params, permit) =>
      val targetRid = ridRegistry.saveLookup(Origin(sender, rid), None, None)
      sendRequest(InvokeRequest(targetRid, translatePath(path), params, permit))
  }

  /**
   * Handles Subscribe request.
   */
  private val handleSubscribeRequest: PartialFunction[DSARequest, Unit] = {
    case req @ SubscribeRequest(rid, _) =>
      route(ResponseEnvelope(connInfo.linkPath, null, List(DSAResponse(rid, Some(StreamState.Closed)))), sender)
      val srcPath = req.path // to ensure there's only one path (see requester actor)
      val origin = Origin(sender, srcPath.sid)
      sidRegistry.lookupByPath(srcPath.path) match {
        case None =>
          val targetSid = sidRegistry.saveLookup(origin, Some(srcPath.path), None)
          val tgtPath = srcPath.copy(path = translatePath(srcPath.path), sid = targetSid)
          sendRequest(SubscribeRequest(ridRegistry.saveEmpty, tgtPath))
        case Some(rec) =>
          sidRegistry.addOrigin(origin, rec)
          rec.lastResponse foreach { rsp =>
            val sourceRow = replaceSid(rsp.updates.get.head, origin.sourceId)
            val response = DSAResponse(0, rsp.stream, Some(List(sourceRow)), rsp.columns, rsp.error)
            route(ResponseEnvelope(connInfo.linkPath, null, List(response)), origin.source)
          }
      }
  }

  /**
   * Handles Unsubscribe request.
   */
  private val handleUnsubscribeRequest: PartialFunction[DSARequest, Unit] = {
    case req @ UnsubscribeRequest(rid, _) =>
      val origin = Origin(sender, req.sid) // to ensure there's only one sid (see requester actor)
      sidRegistry.removeOrigin(origin) map { rec =>
        if (rec.origins.isEmpty)
          sendRequest(UnsubscribeRequest(ridRegistry.saveEmpty, rec.targetId))
        route(ResponseEnvelope(connInfo.linkPath, null, List(DSAResponse(rid, Some(StreamState.Closed)))), sender)
      } getOrElse
        log.warning(s"$ownId: did not find the original Subscribe for SID=${req.sid}")
  }

  /**
   * Handles Close request.
   */
  private val handleCloseRequest: PartialFunction[DSARequest, Unit] = {
    case CloseRequest(rid) =>
      val origin = Origin(sender, rid)
      val record = ridRegistry.removeOrigin(origin)
      record match {
        case None                             => log.warning(s"$ownId: did not find the original request for Close($rid)")
        case Some(rec) if rec.origins.isEmpty => sendRequest(CloseRequest(rec.targetId))
        case Some(rec) if rec.path.isDefined  => route(ResponseEnvelope(connInfo.linkPath, null, List(DSAResponse(rid, Some(StreamState.Closed)))), sender)
        case _                                => // do nothing
      }
  }

  /**
   * Handles a request coming from another actor.
   */
  private def handleRequest(request: DSARequest) = {
    val handler = handleListRequest orElse
      handlePassthroughRequest orElse
      handleSubscribeRequest orElse
      handleUnsubscribeRequest orElse
      handleCloseRequest

    try {
      handler(request)
    } catch {
      case NonFatal(e) => log.error(s"$ownId: error handling request $request", e)
    }
  }

  /**
   * Routes multiple responses.
   */
  private def routeResponses(responses: Iterable[DSAResponse]) =
    responses foreach (routeSubscribeResponse orElse routeNonSubscribeResponse)

  /**
   * Splits the response updates in individual row, translates each update's SID into
   * (potentially) multiple source SIDs and routes one response per source SID.
   */
  private def routeSubscribeResponse: PartialFunction[DSAResponse, Unit] = {
    case rsp @ DSAResponse(0, stream, updates, columns, error) =>
      val list = updates.getOrElse(Nil)
      if (list.isEmpty)
        log.warning(s"Cannot find updates in Subscribe response $rsp")
      else {
        list.foreach { row =>
          val targetSid = extractSid(row)
          val rec = sidRegistry.lookupByTargetId(targetSid).get
          rec.origins foreach { origin =>
            val sourceRow = replaceSid(row, origin.sourceId)
            val response = DSAResponse(0, stream, Some(List(sourceRow)), columns, error)
            route(ResponseEnvelope(connInfo.linkPath, null, List(response)), origin.source)
          }
          rec.lastResponse = Some(DSAResponse(0, stream, Some(List(row)), columns, error))
          if (stream == StreamState.Closed)
            sidRegistry.removeLookup(rec)
        }
      }
  }

  /**
   * Extracts SID from an update row.
   */
  private val extractSid: PartialFunction[DSAVal, Int] = {
    case v: ArrayValue => v.value.head.asInstanceOf[NumericValue].value.intValue
    case v: MapValue   => v.value("sid").asInstanceOf[NumericValue].value.intValue
  }

  /**
   * Replaces SID in an update row.
   */
  private def replaceSid(row: DSAVal, sid: Int) = row match {
    case v: ArrayValue => ArrayValue(sid :: v.value.tail.toList)
    case v: MapValue   => MapValue(v.value + ("sid" -> sid))
    case v             => v
  }

  /**
   * Routes a non-Subscribe response.
   */
  private val routeNonSubscribeResponse: PartialFunction[DSAResponse, Unit] = {
    case response if response.rid != 0 =>
      val record = ridRegistry.lookupByTargetId(response.rid)
      record match {
        case None => log.warning(s"$ownId: did not find the route for $response")
        case Some(rec) =>
          rec.origins foreach { origin =>
            val envelope = ResponseEnvelope(connInfo.linkPath, null, List(response.copy(rid = origin.sourceId)))
            route(envelope, origin.source)
          }
          rec.lastResponse = Some(response)
          if (response.stream == StreamState.Closed)
            ridRegistry.removeLookup(rec)
      }
  }

  /**
   * Sending the envelope to another actor.
   */
  private def route(envelope: ResponseEnvelope, ref: ActorRef) = {
    log.debug(s"$ownId: routing $envelope to $ref")
    ref ! envelope
  }

  /**
   * Removes the linkPath prefix from the path.
   */
  private def translatePath(path: String) = {
    val chopped = path.drop(connInfo.linkPath.size)
    if (chopped.isEmpty) "/" else chopped
  }
}