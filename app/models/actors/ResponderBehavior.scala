package models.actors

import scala.util.control.NonFatal

import models.{ HandlerResult, MessageRouter, Origin, RequestEnvelope }
import models.rpc._

/**
 * Handles communication with a remote DSLink in Responder mode.
 */
trait ResponderBehavior { this: AbstractWebSocketActor =>

  type RequestHandler = PartialFunction[(String, DSARequest), HandlerResult]

  // for request routing
  def router: MessageRouter

  // lookup registries for SID (Subscribe/Unsubscribe) and RID (all other) requests
  private val ridRegistry = new CallRegistry(1)
  private val sidRegistry = new CallRegistry(1)

  /**
   * Processes incoming messages from Responder DSLink and dispatches requests to it.
   */
  val responderBehavior: Receive = {
    case e @ RequestEnvelope(from, _, false, requests) =>
      log.debug(s"$ownId: received unconfirmed $e")
      processRequests(from, requests)
    case e @ RequestEnvelope(_, _, true, requests) =>
      log.debug(s"$ownId: received confirmed $e")
      sendRequests(requests: _*)
    case m @ ResponseMessage(msg, _, responses) =>
      log.info(s"$ownId: received $m from WebSocket")
      sendAck(msg)
      processResponses(responses)
  }

  /**
   * Processes the requests: forwards appropriate messages to the WebSocket and routes the responses
   * back to the originator.
   */
  private def processRequests(from: String, requests: Seq[DSARequest]) = {
    val handler = handleListRequest orElse handlePassthroughRequest orElse
      handleSubscribeRequest orElse handleUnsubscribeRequest orElse handleCloseRequest

    val results = requests map (request => try {
      handler(from -> request)
    } catch {
      case NonFatal(e) => log.error(s"$ownId: error handling request $request - {}", e); HandlerResult.Empty
    })

    log.debug("RID lookups: " + ridRegistry.info)
    log.debug("SID lookups: " + sidRegistry.info)

    sendRequests(results.flatMap(_.requests): _*)

    router.routeHandledResponses(connInfo.linkPath, from, results.flatMap(_.responses): _*) recover {
      case NonFatal(e) => log.error(s"$ownId: error routing the response {}", e)
    }
  }

  /**
   * Processes the responses and routes them to their destinations.
   */
  private def processResponses(responses: Iterable[DSAResponse]) =
    if (router.delegateResponseHandling)
      router.routeUnhandledResponses(connInfo.linkPath, responses.toSeq: _*)
    else {
      val handler = handleSubscribeResponse orElse handleNonSubscribeResponse

      val results = responses flatMap handler

      log.debug("RID lookups: " + ridRegistry.info)
      log.debug("SID lookups: " + sidRegistry.info)

      results groupBy (_._1) mapValues (_.map(_._2)) foreach {
        case (to, rsps) => router.routeHandledResponses(connInfo.linkPath, to, rsps.toSeq: _*) recover {
          case NonFatal(e) => log.error(s"$ownId: error routing the responses {}", e)
        }
      }
    }

  /**
   * Handles List request.
   */
  private val handleListRequest: RequestHandler = {
    case (from, ListRequest(rid, path)) =>
      val origin = Origin(from, rid)
      ridRegistry.lookupByPath(path) match {
        case None =>
          val targetRid = ridRegistry.saveLookup(origin, Some(path))
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

    def tgtId(from: String, srcId: Int) = ridRegistry.saveLookup(Origin(from, srcId))

    val pass: PartialFunction[(String, DSARequest), DSARequest] = {
      case (from, SetRequest(rid, path, value, permit))     => SetRequest(tgtId(from, rid), translatePath(path), value, permit)
      case (from, RemoveRequest(rid, path))                 => RemoveRequest(tgtId(from, rid), translatePath(path))
      case (from, InvokeRequest(rid, path, params, permit)) => InvokeRequest(tgtId(from, rid), translatePath(path), params, permit)
    }

    pass andThen HandlerResult.apply
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
          val targetSid = sidRegistry.saveLookup(sidOrigin, Some(srcPath.path), None)
          val targetRid = ridRegistry.saveEmpty
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
          List(UnsubscribeRequest(ridRegistry.saveEmpty, rec.targetId))
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
          rec.lastResponse = Some(response)
          if (response.stream == Some(StreamState.Closed))
            ridRegistry.removeLookup(rec)
          rec.origins map { origin =>
            (origin.source, response.copy(rid = origin.sourceId))
          } toSeq
      }
  }

  /**
   * Removes the linkPath prefix from the path.
   */
  private def translatePath(path: String) = {
    val chopped = path.drop(connInfo.linkPath.size)
    if (chopped.isEmpty) "/" else chopped
  }
}