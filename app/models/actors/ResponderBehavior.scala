package models.actors

import scala.util.Try
import scala.util.control.NonFatal

import akka.actor.{ ActorRef, actorRef2Scala }
import models._

/**
 * Handles communication with a remote DSLink in Responder mode.
 */
trait ResponderBehavior { this: AbstractWebSocketActor =>
  import DSAValue._

  // binds original RID/SID with the request sender, used to route the responses
  private val sourcesByRid = collection.mutable.Map.empty[Int, ActorRef]
  private val sourcesBySid = collection.mutable.Map.empty[Int, ActorRef]

  // binds original (source) RID/SID and the target RID/SID
  // source->target lookup is used by Close and Unsubscribe
  // target->source lookup is used for routing responses
  private val ridLookup = new IdLookup(1)
  private val sidLookup = new IdLookup(1)

  /**
   * Processes incoming messages from Responder DSLink and dispatches requests to it.
   */
  val responderBehavior: Receive = {
    case e @ RequestEnvelope(request) =>
      log.debug(s"$ownId: received $e")
      handleRequest(request)
    case m @ ResponseMessage(msg, ack, responses) =>
      log.info(s"$ownId: received $m from WebSocket")
      sendAck(msg)
      routeResponses(responses)
  }

  /**
   * Handles a request coming from another actor.
   */
  private def handleRequest(request: DSARequest) = request match {
    case ListRequest(rid, path) =>
      sourcesByRid(rid) = sender
      sendRequest(ListRequest(ridLookup.createTargetId(rid), augmentPath(path)))
    case SetRequest(rid, path, value, permit) =>
      sourcesByRid(rid) = sender
      sendRequest(SetRequest(ridLookup.createTargetId(rid), augmentPath(path), value, permit))
    case RemoveRequest(rid, path) =>
      sourcesByRid(rid) = sender
      sendRequest(RemoveRequest(ridLookup.createTargetId(rid), augmentPath(path)))
    case InvokeRequest(rid, path, params, permit) =>
      sourcesByRid(rid) = sender
      sendRequest(InvokeRequest(ridLookup.createTargetId(rid), augmentPath(path), params, permit))
    case SubscribeRequest(rid, paths) =>
      sourcesByRid(rid) = sender
      sourcesBySid(paths.head.sid) = sender
      val srcPath = paths.head
      val tgtPath = srcPath.copy(path = augmentPath(srcPath.path), sid = sidLookup.createTargetId(srcPath.sid))
      sendRequest(SubscribeRequest(ridLookup.createTargetId(rid), tgtPath))
    case UnsubscribeRequest(rid, sids) => Try(sidLookup.targetId(sids.head)).map { targetSid =>
      sourcesByRid(rid) = sender
      sendRequest(UnsubscribeRequest(ridLookup.createTargetId(rid), targetSid))
    } recover {
      case NonFatal(e) => log.error(s"$ownId: target SID not found for ${sids.head}")
    }
    case CloseRequest(rid) => Try(ridLookup.targetId(rid)).map { targetRid =>
      sourcesByRid(rid) = sender
      sendRequest(CloseRequest(targetRid))
    } recover {
      case NonFatal(e) => log.error(s"$ownId: target RID not found for $rid")
    }
  }

  /**
   * Routes multiple responses.
   */
  private def routeResponses(responses: Iterable[DSAResponse]) = {

    def splitResponse(response: DSAResponse) = if (response.rid == 0) {
      response.updates.getOrElse(Nil) map { update =>
        DSAResponse(0, response.stream, Some(List(update)), response.columns, response.error)
      }
    } else response :: Nil

    responses flatMap splitResponse foreach routeResponse
  }

  /**
   * Routes a single response.
   */
  private val routeResponse = routeSubscribeResponse orElse routeNonSubscribeResponse

  /**
   * Routes a Subscribe response. Can only be done if updates are present.
   */
  private def routeSubscribeResponse: PartialFunction[DSAResponse, Unit] = {
    case response if response.rid == 0 & !response.updates.getOrElse(Nil).isEmpty =>
      val updates = response.updates.getOrElse(Nil) collect {
        case v: ArrayValue =>
          val targetSid = v.value.head.asInstanceOf[NumericValue].value.intValue
          val sourceSid = sidLookup.sourceId(targetSid)
          val source = sourcesBySid(sourceSid)
          (ArrayValue(sourceSid :: v.value.tail.toList), source)
        case v: MapValue =>
          val targetSid = v.value("sid").asInstanceOf[NumericValue].value.intValue
          val sourceSid = sidLookup.sourceId(targetSid)
          val source = sourcesBySid(sourceSid)
          (MapValue(v.value + ("sid" -> sourceSid)), source)
      }
      updates foreach {
        case (update, source) => route(ResponseEnvelope(response.copy(updates = Some(List(update)))), source)
      }
    case response if response.rid == 0 => log.warning(s"Cannot find route for response: $response")
  }

  /**
   * Routes a non-Subscribe response.
   */
  private def routeNonSubscribeResponse: PartialFunction[DSAResponse, Unit] = {
    case response if response.rid != 0 =>
      val sourceRid = ridLookup.sourceId(response.rid)
      val source = sourcesByRid(sourceRid)
      route(ResponseEnvelope(response.copy(rid = sourceRid)), source)
  }

  /**
   * Sending the envelope to another actor.
   */
  private def route(envelope: ResponseEnvelope, ref: ActorRef) = {
    ref ! envelope
    log.debug(s"$ownId: routed $envelope to $ref")
  }

  /**
   * Removes the linkPath prefix from the path.
   */
  private def augmentPath(path: String) = {
    val chopped = path.drop(connInfo.linkPath.size)
    if (chopped.isEmpty) "/" else chopped
  }
}