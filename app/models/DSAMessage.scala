package models

/**
 * Base trait for DSA messages.
 */
sealed trait DSAMessage

/**
 * A blank message.
 */
case object EmptyMessage extends DSAMessage

/**
 * Message sent by the broker once the connection has been established
 */
case class AllowedMessage(allowed: Boolean, salt: Long) extends DSAMessage

/**
 * Does not contain requests or responses, purely for keeping the connection alive.
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