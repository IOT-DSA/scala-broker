package models.akka

import scala.util.control.NonFatal

import models._
import models.rpc._
import models.rpc.DSAValue.{ DSAVal, StringValue, array }
import models.splitPath

/**
 * Handles communication with a remote DSLink in Responder mode.
 */
trait ResponderBehavior { me: DSLinkActor =>

  type RequestHandler = PartialFunction[(String, DSARequest), HandlerResult]

  // lookup registries for SID (Subscribe/Unsubscribe) and RID (all other) requests
  private val ridRegistry = new CallRegistry(1)
  private val sidRegistry = new CallRegistry(1)

  private val attributes = collection.mutable.Map.empty[String, Map[String, DSAVal]]

  val responderBehavior: Receive = {
    case env @ RequestEnvelope(from, to, requests) =>
      log.debug(s"$ownId: received $env")
      val result = processRequests(from, requests)
      // TODO just temporary, implement connected/disconnected behavior
      if (!result.requests.isEmpty)
        ws foreach (_ ! RequestEnvelope(from, to, result.requests))
      if (!result.responses.isEmpty)
        sender ! ResponseEnvelope(to, from, result.responses)

    case m @ ResponseMessage(_, _, responses) =>
      log.debug(s"$ownId: received $m")
      processResponses(responses) foreach {
        case (to, rsps) => dsaSend(to, ResponseEnvelope(linkPath, to, rsps)) 
      }
  }

  /**
   * Processes the requests and returns requests that need to be forwaded to their destinations
   * as well as the responses that need to be delivered to the originators.
   */
  def processRequests(from: String, requests: Seq[DSARequest]): HandlerResult = {
    val handler = handleListRequest orElse handlePassthroughRequest orElse
      handleSubscribeRequest orElse handleUnsubscribeRequest orElse handleCloseRequest

    val results = requests map (request => try {
      handler(from -> request)
    } catch {
      case NonFatal(e) => log.error(s"$ownId: error handling request $request - {}", e); HandlerResult.Empty
    })

    log.debug("RID lookups: " + ridRegistry.info)
    log.debug("SID lookups: " + sidRegistry.info)

    HandlerResult.flatten(results)
  }

  /**
   * Processes the responses and returns the translated ones groupped by their destinations.
   */
  def processResponses(responses: Seq[DSAResponse]): Map[String, Seq[DSAResponse]] = {
    val handler = handleSubscribeResponse orElse handleNonSubscribeResponse

    val results = responses flatMap handler

    log.debug("RID lookups: " + ridRegistry.info)
    log.debug("SID lookups: " + sidRegistry.info)

    results groupBy (_._1) mapValues (_.map(_._2))
  }

  /**
   * Handles List request.
   */
  private val handleListRequest: RequestHandler = {
    case (from, ListRequest(rid, path)) =>
      val origin = Origin(from, rid)
      ridRegistry.lookupByPath(path) match {
        case None =>
          val targetRid = ridRegistry.saveLookup(origin, DSAMethod.List, Some(path))
          HandlerResult(ListRequest(targetRid, translatePath(path)))
        case Some(rec) =>
          ridRegistry.addOrigin(origin, rec)
          rec.lastResponse map { rsp =>
            HandlerResult(rsp.copy(rid = origin.sourceId))
          } getOrElse HandlerResult.Empty
      }
  }

  /**
   * Handles Set, Remove and Invoke requests.
   */
  private val handlePassthroughRequest: RequestHandler = {

    def tgtId(from: String, srcId: Int, method: DSAMethod.DSAMethod) = ridRegistry.saveLookup(Origin(from, srcId), method)

    def saveAttribute(nodePath: String, name: String, value: DSAVal) = {
      log.debug(s"$ownId: saving attribute under $nodePath: $name = $value")
      val attrMap = attributes.getOrElse(nodePath, Map.empty)
      attributes(nodePath) = attrMap + (name -> value)
    }

    // translates request to request for forwarding to responder 
    // or request to response to handle locally and return response to the requester
    val pass: PartialFunction[(String, DSARequest), Either[DSARequest, DSAResponse]] = {
      case (from, SetRequest(rid, path, value, permit)) if isAttribute(path) =>
        val (nodePath, attrName) = splitPath(path)
        saveAttribute(nodePath, attrName.get, value)
        Right(DSAResponse(rid, Some(StreamState.Closed)))
      case (from, SetRequest(rid, path, value, permit)) =>
        Left(SetRequest(tgtId(from, rid, DSAMethod.Set), translatePath(path), value, permit))
      case (from, RemoveRequest(rid, path)) =>
        Left(RemoveRequest(tgtId(from, rid, DSAMethod.Remove), translatePath(path)))
      case (from, InvokeRequest(rid, path, params, permit)) if path.endsWith("/" + AddAttributeAction) =>
        val attrName = params("name").value.toString
        val nodePath = path.dropRight(AddAttributeAction.size + 1)
        saveAttribute(nodePath, attrName, params("value"))
        Right(DSAResponse(rid, Some(StreamState.Closed)))
      case (from, InvokeRequest(rid, path, params, permit)) if path.endsWith("/" + SetValueAction) =>
        val attrPath = path.dropRight(SetValueAction.size + 1)
        val attrValue = params("value")
        Left(SetRequest(tgtId(from, rid, DSAMethod.Set), translatePath(attrPath), attrValue, permit))
      case (from, InvokeRequest(rid, path, params, permit)) =>
        Left(InvokeRequest(tgtId(from, rid, DSAMethod.Invoke), translatePath(path), params, permit))
    }

    pass andThen (_.fold(HandlerResult.apply, HandlerResult.apply))
  }

  /**
   * Handles Subscribe request.
   */
  private val handleSubscribeRequest: RequestHandler = {
    case (from, req @ SubscribeRequest(rid, _)) =>
      val srcPath = req.path // to ensure there's only one path (see requester actor)
      val sidOrigin = Origin(from, srcPath.sid)
      val result = sidRegistry.lookupByPath(srcPath.path) match {
        case None =>
          val targetSid = sidRegistry.saveLookup(sidOrigin, DSAMethod.Subscribe, Some(srcPath.path), None)
          val targetRid = ridRegistry.saveEmpty(DSAMethod.Subscribe)
          val tgtPath = srcPath.copy(path = translatePath(srcPath.path), sid = targetSid)
          HandlerResult(SubscribeRequest(targetRid, tgtPath))
        case Some(rec) =>
          sidRegistry.addOrigin(sidOrigin, rec)
          rec.lastResponse map { rsp =>
            val sourceRow = replaceSid(rsp.updates.get.head, sidOrigin.sourceId)
            val update = DSAResponse(0, rsp.stream, Some(List(sourceRow)), rsp.columns, rsp.error)
            HandlerResult(update)
          } getOrElse HandlerResult.Empty
      }
      result.copy(responses = DSAResponse(rid, Some(StreamState.Closed)) +: result.responses)
  }

  /**
   * Handles Unsubscribe request.
   */
  private val handleUnsubscribeRequest: RequestHandler = {
    case (from, req @ UnsubscribeRequest(rid, _)) =>
      val origin = Origin(from, req.sid) // to ensure there's only one sid (see requester actor)
      sidRegistry.removeOrigin(origin) map { rec =>
        val wsReqs = if (rec.origins.isEmpty) {
          sidRegistry.removeLookup(rec)
          List(UnsubscribeRequest(ridRegistry.saveEmpty(DSAMethod.Unsubscribe), rec.targetId))
        } else Nil
        HandlerResult(wsReqs, List(DSAResponse(rid, Some(StreamState.Closed))))
      } getOrElse {
        log.warning(s"$ownId: did not find the original Subscribe for SID=${req.sid}")
        HandlerResult.Empty
      }
  }

  /**
   * Handles Close request.
   */
  private val handleCloseRequest: RequestHandler = {
    case (from, CloseRequest(rid)) =>
      val origin = Origin(from, rid)
      val record = ridRegistry.removeOrigin(origin)
      record match {
        case None =>
          log.warning(s"$ownId: did not find the original request for Close($rid)")
          HandlerResult.Empty
        case Some(rec) =>
          if (rec.origins.isEmpty) ridRegistry.removeLookup(rec)
          val reqs = if (rec.origins.isEmpty) List(CloseRequest(rec.targetId)) else Nil
          val rsps = if (rec.path.isDefined) List(DSAResponse(rid, Some(StreamState.Closed))) else Nil
          HandlerResult(reqs, rsps)
      }
  }

  /**
   * Splits the response updates in individual row, translates each update's SID into
   * (potentially) multiple source SIDs and creates one response per source SID.
   */
  private def handleSubscribeResponse: PartialFunction[DSAResponse, Seq[(String, DSAResponse)]] = {
    case rsp @ DSAResponse(0, stream, updates, columns, error) =>
      val list = updates.getOrElse(Nil)
      if (list.isEmpty) {
        log.warning(s"Cannot find updates in Subscribe response $rsp")
        Nil
      } else list flatMap { row =>
        val targetSid = extractSid(row)
        val rec = sidRegistry.lookupByTargetId(targetSid).get
        rec.lastResponse = Some(DSAResponse(0, stream, Some(List(row)), columns, error))
        if (stream == Some(StreamState.Closed))
          sidRegistry.removeLookup(rec)
        rec.origins map { origin =>
          val sourceRow = replaceSid(row, origin.sourceId)
          val response = DSAResponse(0, stream, Some(List(sourceRow)), columns, error)
          (origin.source, response)
        }
      }
  }

  /**
   * Handles a non-Subscribe response.
   */
  private val handleNonSubscribeResponse: PartialFunction[DSAResponse, Seq[(String, DSAResponse)]] = {
    case response if response.rid != 0 =>
      val record = ridRegistry.lookupByTargetId(response.rid)
      record match {
        case None =>
          log.warning(s"$ownId: did not find the route for $response")
          Nil
        case Some(rec) =>
          // adjust response for stored attributes, if appropriate
          val rsp = if (rec.method == DSAMethod.List) {
            val attrUpdates = attributes.getOrElse(rec.path.get, Map.empty) map {
              case (name, value) => array(name, value)
            }
            val oldUpdates = response.updates getOrElse Nil
            response.copy(updates = Some(oldUpdates ++ attrUpdates))
          } else response
          rec.lastResponse = Some(rsp)
          if (rsp.stream == Some(StreamState.Closed))
            ridRegistry.removeLookup(rec)
          rec.origins map { origin =>
            (origin.source, rsp.copy(rid = origin.sourceId))
          } toSeq
      }
  }

  /**
   * Removes the linkPath prefix from the path.
   */
  private def translatePath(path: String) = {
    val chopped = path.drop(linkPath.size)
    if (!chopped.startsWith("/")) "/" + chopped else chopped
  }  
}