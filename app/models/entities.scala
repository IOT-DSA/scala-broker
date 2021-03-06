package models

import _root_.akka.routing.Routee
import models.akka.QoS
import models.rpc.DSAValue.DSAMap
import models.rpc.{DSARequest, DSAResponse}

/**
  * Envelope for internal request routing.
  */
case class RequestEnvelope(requests: Seq[DSARequest], header: Option[DSAMap] = None) {

  /**
    * Outputs only the first request for compact logging.
    */
  override def toString = {
    val headerStr = header.map(m => ", " + m.toString).getOrElse("")
    if (requests.size < 2)
      s"RequestEnvelope($requests$headerStr)"
    else
      s"RequestEnvelope(List(${requests.head},...${requests.size - 1} more)$headerStr)"
  }
}

/**
  * Envelope for internal response routing.
  */
case class ResponseEnvelope(responses: Seq[DSAResponse]) {

  /**
    * Outputs only the first response for compact logging.
    */
  override def toString = if (responses.size < 2)
    s"ResponseEnvelope($responses})"
  else
    s"ResponseEnvelope(List(${responses.head},...${responses.size - 1} more))"
}

case class SubscriptionResponseEnvelope(response: DSAResponse, sid: Int, qos: QoS.Level) {
  /**
    * Outputs only the first response for compact logging.
    */
  override def toString =
    s"SubscriptionResponseEnvelope(response:$response, sid:$sid, qos:$qos)"
}

/**
  * Used in call records to store the subscribers for future responses.
  */
case class Origin(source: Routee, sourceId: Int) {
  override def toString = s"Origin(${source}:$sourceId)"
}

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

  def flatten(seq: Seq[HandlerResult]): HandlerResult = apply(seq.flatMap(_.requests), seq.flatMap(_.responses))
}