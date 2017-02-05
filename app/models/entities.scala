package models

import models.rpc.{ DSARequest, DSAResponse }

/**
 * Envelope for internal request routing. If `confirmed` is set to `true`, that means the
 * requests have already been processed/translated and need to delivered to the destination
 * (WebSocket) as is.
 */
case class RequestEnvelope(from: String, to: String, confirmed: Boolean, requests: Seq[DSARequest]) {

  /**
   * Outputs only the first request for compact logging.
   */
  override def toString = if (requests.size < 2)
    s"RequestEnvelope($from,$to,$confirmed,$requests})"
  else
    s"RequestEnvelope($from,$to,$confirmed,List(${requests.head},...${requests.size - 1} more))"
}

/**
 * Envelope for internal response routing.
 */
case class ResponseEnvelope(from: String, to: String, responses: Seq[DSAResponse]) {

  /**
   * Outputs only the first response for compact logging.
   */
  override def toString = if (responses.size < 2)
    s"ResponseEnvelope($from,$to,$responses})"
  else
    s"ResponseEnvelope($from,$to,List(${responses.head},...${responses.size - 1} more))"
}

/**
 * Used in call records to store the subscribers for future responses.
 */
case class Origin(source: String, sourceId: Int)

/**
 * Combines the requests that need to be dispatched to the WebSocket and
 * responses that need to be sent back to the requester's actor.
 */
case class HandlerResult(requests: Seq[DSARequest], responses: Seq[DSAResponse])

/**
 * Factory for [[HandlerResult]] instances.
 */
object HandlerResult {

  val Empty = HandlerResult(Nil, Nil)

  def apply(request: DSARequest): HandlerResult = apply(List(request), Nil)

  def apply(response: DSAResponse): HandlerResult = apply(Nil, List(response))

  def apply(request: DSARequest, response: DSAResponse): HandlerResult = apply(List(request), List(response))
}