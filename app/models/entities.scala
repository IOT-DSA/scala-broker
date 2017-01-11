package models

/**
 * Base trait for DSA messages.
 */
sealed trait DSAMessage {
  val msg: Int
  val ack: Option[Int]
}

/**
 * Does not contain requests or responses, purely for pinging.
 */
case class PingMessage(msg: Int, ack: Option[Int] = None) extends DSAMessage

/**
 * Encapsulates multiple DSA requests along with msg code and optional acknowledgement code.
 */
case class RequestMessage(msg: Int, ack: Option[Int] = None, requests: List[DSARequest] = Nil) extends DSAMessage

/**
 * Encapsulates multiple DSA responses along with msg code and optional acknowledgement code.
 */
case class ResponseMessage(msg: Int, ack: Option[Int] = None, responses: List[DSAResponse] = Nil) extends DSAMessage