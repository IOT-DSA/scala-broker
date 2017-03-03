package models.actors

import scala.util.control.NonFatal

import models.{ MessageRouter, RequestEnvelope }
import models.rpc.ResponseMessage

/**
 * Handles communication with a remote DSLink in Responder mode.
 */
trait ResponderBehavior extends RPCProcessor { me: AbstractWebSocketActor =>

  protected val ownPath = connInfo.linkPath

  // for request routing
  def router: MessageRouter

  /**
   * Processes incoming messages from Responder DSLink and dispatches requests to it.
   */
  val responderBehavior: Receive = {
    case e @ RequestEnvelope(from, _, false, requests) =>
      log.debug(s"$ownId: received unconfirmed $e")
      val result = processRequests(from, requests)
      sendRequests(result.requests: _*)
      router.routeHandledResponses(connInfo.linkPath, from, result.responses: _*) recover {
        case NonFatal(e) => log.error(s"$ownId: error routing the response {}", e)
      }

    case e @ RequestEnvelope(_, _, true, requests) =>
      log.debug(s"$ownId: received confirmed $e")
      sendRequests(requests: _*)

    case m @ ResponseMessage(msg, _, responses) =>
      log.info(s"$ownId: received $m from WebSocket")
      sendAck(msg)
      if (router.delegateResponseHandling)
        router.routeUnhandledResponses(connInfo.linkPath, responses.toSeq: _*)
      else processResponses(responses) foreach {
        case (to, rsps) => router.routeHandledResponses(connInfo.linkPath, to, rsps.toSeq: _*) recover {
          case NonFatal(e) => log.error(s"$ownId: error routing the responses {}", e)
        }
      }
  }
}