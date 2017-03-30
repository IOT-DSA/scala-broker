package models.actors

import scala.util.control.NonFatal

import models.{ MessageRouter, RequestEnvelope }
import models.rpc.ResponseMessage

/**
 * Handles communication with a remote DSLink in Responder mode.
 */
trait ResponderBehavior { me: AbstractWebSocketActor =>

  private val processor = context.actorOf(RRProcessorActor.props(connInfo.linkPath, cache))

  // for response routing  
  def router: MessageRouter

  /**
   * Processes incoming messages from Responder DSLink and dispatches requests to it.
   */
  val responderBehavior: Receive = {
    case e @ RequestEnvelope(_, _, requests) =>
      log.debug(s"$ownId: received $e")
      sendRequests(requests: _*)

    case m @ ResponseMessage(msg, _, responses) =>
      log.info(s"$ownId: received $m from WebSocket")
      sendAck(msg)
      router.routeResponses(connInfo.linkPath, "n/a", responses.toSeq: _*) recover {
        case NonFatal(e) => log.error(s"$ownId: error routing the responses: {}", e.getMessage)
      }
  }
}