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
 * Lookup record resolved by targetRid.
 */
case class TargetLookupRecord(method: DSAMethod, origin: Origin, path: Option[String])

/**
 * Lookup record resolved by origin.
 */
case class SourceLookupRecord(method: DSAMethod, targetId: Int)

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

  // generates RIDs for all new calls
  private var targetRids = new IntCounter(1)

  // generates SIDs for all new calls
  private var targetSids = new IntCounter(1)

  // used for response lookup
  private val tgtRidLookup = collection.mutable.Map.empty[Int, TargetLookupRecord]

  // used only for Invoke
  private val srcRidLookup = collection.mutable.Map.empty[Origin, SourceLookupRecord]

  // for List calls path->tgtRid
  private val listPathLookup = collection.mutable.Map.empty[String, Int]

  // for Subscribe/Unsubscribe tgtSid -> path
  private val tgtSidLookup = collection.mutable.Map.empty[Int, String]

  // for Subscribe calls path->tgtSid
  private val subsPathLookup = collection.mutable.Map.empty[String, Int]

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
      // TODO until proper connected/disconnected behavior is implemented
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

    log.info("Tgt RID lookups: " + tgtRidLookup.size)
    log.info("Src RID lookups: " + srcRidLookup.size)
    log.info("List Path lookups: " + listPathLookup.size)
    log.info("Subs Path lookups: " + subsPathLookup.size)

    HandlerResult.flatten(results)
  }

  /**
   * Processes the responses and returns the translated ones groupped by their destinations.
   */
  def processResponses(responses: Seq[DSAResponse]): Map[ActorRef, Seq[DSAResponse]] = {
    val handler = handleSubscribeResponse orElse handleNonSubscribeResponse

    val results = responses flatMap handler

    log.info("Tgt RID lookups: " + tgtRidLookup.size)
    log.info("Src RID lookups: " + srcRidLookup.size)
    log.info("List Path lookups: " + listPathLookup.size)
    log.info("Subs Path lookups: " + subsPathLookup.size)

    results groupBy (_._1) mapValues (_.map(_._2))
  }

  /**
   * Handles List request.
   */
  private val handleListRequest: RequestHandler = {
    case ListRequest(rid, path) =>
      val origin = Origin(sender, rid)
      listPathLookup.get(path) match {
        case None =>
          val tgtId = targetRids.inc
          listPathLookup(path) = tgtId
          tgtRidLookup(tgtId) = TargetLookupRecord(DSAMethod.List, null, Some(path)) // TODO origin not needed here
          listRouter ! AddOrigin(tgtId, origin)
          HandlerResult(ListRequest(tgtId, translatePath(path)))
        case Some(tgtId) =>
          listRouter ! AddOrigin(tgtId, origin)
          HandlerResult.Empty
      }
  }

  /**
   * Translates the original request before sending it to responder link.
   */
  private def handlePassthroughRequest: RequestHandler = {

    def tgtId(srcId: Int, method: DSAMethod) = targetRids.inc having { tgtId =>
      val origin = Origin(sender, srcId)
      tgtRidLookup(tgtId) = TargetLookupRecord(method, origin, None)
      srcRidLookup(origin) = SourceLookupRecord(method, tgtId)
    }

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

      subsPathLookup.get(srcPath.path) match {
        case None =>
          val tgtRid = targetRids.inc
          tgtRidLookup(tgtRid) = TargetLookupRecord(DSAMethod.Subscribe, ridOrigin, None) // origin IS needed
          val tgtSid = targetSids.inc
          subsPathLookup(srcPath.path) = tgtSid
          tgtSidLookup(tgtSid) = srcPath.path
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
      // TODO this is a temporary solution, needs to be async using become() and stash()
      val ibox = inbox()
      subsRouter.tell(LookupTargetId(sidOrigin), ibox.getRef)
      ibox.receive().asInstanceOf[Option[Int]] map { targetSid =>
        subsRouter ! RemoveOrigin(sidOrigin)
        subsRouter.tell(Broadcast(GetOriginCount(targetSid)), ibox.getRef)
        val count = (1 to subsPool.nrOfInstances).map(_ => ibox.receive().asInstanceOf[Int]).sum
        if (count < 1) {
          val path = tgtSidLookup(targetSid)
          subsPathLookup -= path
          tgtSidLookup -= targetSid
          val tgtRid = targetRids.inc
          tgtRidLookup(tgtRid) = TargetLookupRecord(DSAMethod.Unsubscribe, ridOrigin, None)
          HandlerResult(UnsubscribeRequest(tgtRid, List(targetSid)))
        } else
          HandlerResult(DSAResponse(rid, Some(StreamState.Closed)))
      } getOrElse HandlerResult(DSAResponse(rid, Some(StreamState.Closed)))
  }

  /**
   * Handles Close request.
   */
  private val handleCloseRequest: RequestHandler = {
    case CloseRequest(rid) =>
      val origin = Origin(sender, rid)
      srcRidLookup.get(origin) match {
        case Some(SourceLookupRecord(_, tgtId)) => HandlerResult(CloseRequest(tgtId))
        case _ =>
          // assumed to be related to LIST call
          // TODO this is a temporary solution, needs to be async using become() and stash()
          val ibox = inbox()
          listRouter.tell(LookupTargetId(origin), ibox.getRef)
          ibox.receive().asInstanceOf[Option[Int]] map { targetId =>
            listRouter ! RemoveOrigin(origin)
            listRouter.tell(Broadcast(GetOriginCount(targetId)), ibox.getRef)
            val count = (1 to listPool.nrOfInstances).map(_ => ibox.receive().asInstanceOf[Int]).sum
            if (count < 1) {
              val rec = tgtRidLookup(targetId)
              listPathLookup -= rec.path.get
              tgtRidLookup -= targetId
              HandlerResult(CloseRequest(targetId))
            } else
              HandlerResult.Empty
          } getOrElse HandlerResult.Empty
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
      val result = tgtRidLookup.get(rsp.rid) match {
        case Some(TargetLookupRecord(DSAMethod.List, _, _)) =>
          listRouter ! Broadcast(rsp)
          Nil
        case Some(TargetLookupRecord(_, origin, _)) =>
          if (rsp.stream == Some(StreamState.Closed)) {
            tgtRidLookup -= rsp.rid
            srcRidLookup -= origin
          }
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