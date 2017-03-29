package models.kafka

import org.apache.kafka.streams.processor.ProcessorContext

import models.{ HandlerResult, Origin, RequestEnvelope, ResponseEnvelope }
import models.rpc._

/**
 * Handles requests coming from Web Socket.
 */
class RequestHandler extends AbstractTransformer[String, RequestEnvelope, String, (RequestEnvelope, ResponseEnvelope)] {

  type RequestProcessor = PartialFunction[(String, String, DSARequest), HandlerResult]

  private var ridRegistry: CallRegistry = null
  private var sidRegistry: CallRegistry = null

  override def postInit(ctx: ProcessorContext) = {
    ridRegistry = BrokerFlow.RidManager.build(ctx)
    sidRegistry = BrokerFlow.SidManager.build(ctx)
  }

  /**
   * Handles List request.
   */
  private val handleListRequest: RequestProcessor = {
    case (from, to, ListRequest(rid, path)) =>
      val origin = Origin(from, rid)
      ridRegistry.lookupByPath(to, path) match {
        case None =>
          val targetRid = ridRegistry.saveLookup(to, origin, Some(path))
          HandlerResult(ListRequest(targetRid, translatePath(path, to)))
        case Some(rec) =>
          ridRegistry.updateLookup(to, rec.addOrigin(origin))
          rec.lastResponse map { rsp =>
            HandlerResult(rsp.copy(rid = origin.sourceId))
          } getOrElse HandlerResult.Empty
      }
  }

  /**
   * Handles Set, Remove and Invoke requests.
   */
  private val handlePassthroughRequest: RequestProcessor = {

    def tgtId(from: String, to: String, srcId: Int) = ridRegistry.saveLookup(to, Origin(from, srcId))

    val pass: PartialFunction[(String, String, DSARequest), DSARequest] = {
      case (from, to, SetRequest(rid, path, value, permit)) =>
        SetRequest(tgtId(from, to, rid), translatePath(path, to), value, permit)
      case (from, to, RemoveRequest(rid, path)) =>
        RemoveRequest(tgtId(from, to, rid), translatePath(path, to))
      case (from, to, InvokeRequest(rid, path, params, permit)) =>
        InvokeRequest(tgtId(from, to, rid), translatePath(path, to), params, permit)
    }

    pass andThen HandlerResult.apply
  }

  /**
   * Handles Subscribe request.
   */
  private val handleSubscribeRequest: RequestProcessor = {
    case (from, to, req @ SubscribeRequest(rid, _)) =>
      val srcPath = req.path // to ensure there's only one path (see requester actor)
      val sidOrigin = Origin(from, srcPath.sid)
      val result = sidRegistry.lookupByPath(to, srcPath.path) match {
        case None =>
          val targetSid = sidRegistry.saveLookup(to, sidOrigin, Some(srcPath.path))
          val targetRid = ridRegistry.saveEmpty(to)
          val tgtPath = srcPath.copy(path = translatePath(srcPath.path, to), sid = targetSid)
          HandlerResult(SubscribeRequest(targetRid, tgtPath))
        case Some(rec) =>
          sidRegistry.updateLookup(to, rec.addOrigin(sidOrigin))
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
  private val handleUnsubscribeRequest: RequestProcessor = {
    case (from, to, req @ UnsubscribeRequest(rid, _)) =>
      val origin = Origin(from, req.sid) // to ensure there's only one sid (see requester actor)
      sidRegistry.removeOrigin(to, origin) map { rec =>
        val wsReqs = if (rec.origins.isEmpty) {
          sidRegistry.removeLookup(to, rec)
          List(UnsubscribeRequest(ridRegistry.saveEmpty(to), rec.targetId))
        } else Nil
        HandlerResult(wsReqs, List(DSAResponse(rid, Some(StreamState.Closed))))
      } getOrElse {
        log.warn(s"Did not find the original Subscribe for SID=${req.sid}")
        HandlerResult.Empty
      }
  }

  /**
   * Handles Close request.
   */
  private val handleCloseRequest: RequestProcessor = {
    case (from, to, CloseRequest(rid)) =>
      val origin = Origin(from, rid)
      val record = ridRegistry.removeOrigin(to, origin)
      record match {
        case None =>
          log.warn(s"Did not find the original request for Close($rid)")
          HandlerResult.Empty
        case Some(rec) =>
          if (rec.origins.isEmpty) ridRegistry.removeLookup(to, rec)
          val reqs = if (rec.origins.isEmpty) List(CloseRequest(rec.targetId)) else Nil
          val rsps = if (rec.path.isDefined) List(DSAResponse(rid, Some(StreamState.Closed))) else Nil
          HandlerResult(reqs, rsps)
      }
  }

  /**
   * Transforms the request envelope into confirmed requests to be sent to the responder
   * and responses to be sent back to the requester.
   */
  def transform(target: String, env: RequestEnvelope) = {

    val handler = handleListRequest orElse handlePassthroughRequest orElse
      handleSubscribeRequest orElse handleUnsubscribeRequest orElse handleCloseRequest

    val results = env.requests map { request =>
      val args = (env.from, env.to, request)
      handler(args)
    }

    // send to WebSocket
    val wsReqs = results flatMap (_.requests)
    val reqEnvelope = RequestEnvelope(env.from, env.to, wsReqs)

    // route back to requester
    val rsps = results flatMap (_.responses)
    val rspEnvelope = ResponseEnvelope(env.to, env.from, rsps)

    (target, (reqEnvelope, rspEnvelope))
  }

  /**
   * Removes the linkPath prefix from the path.
   */
  private def translatePath(path: String, linkPath: String) = {
    val chopped = path.drop(linkPath.size)
    if (chopped.isEmpty) "/" else chopped
  }
}

/**
 * Factory for [[RequestHandler]] instances.
 */
object RequestHandler extends AbstractTransformerSupplier[String, RequestEnvelope, String, (RequestEnvelope, ResponseEnvelope)] {

  /**
   * Stores required by [[RequestHandler]].
   */
  val StoresNames = BrokerFlow.RidManager.storeNames ++ BrokerFlow.SidManager.storeNames

  /**
   * Creates a new RequestHandler instance.
   */
  def get = new RequestHandler
}