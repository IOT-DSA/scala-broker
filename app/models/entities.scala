package models

import models.rpc.{ DSARequest, DSAResponse }

/**
 * Envelope for internal request routing.
 */
case class RequestEnvelope(from: String, to: String, requests: Seq[DSARequest]) {

  /**
   * Outputs only the first request for compact logging.
   */
  override def toString = if (requests.size < 2)
    s"RequestEnvelope($from,$to,$requests})"
  else
    s"RequestEnvelope($from,$to,List(${requests.head},...${requests.size - 1} more))"
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