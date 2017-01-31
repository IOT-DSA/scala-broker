package models.rpc

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
 * Ping acknowledgement.
 */
case class PongMessage(ack: Int) extends DSAMessage

/**
 * Encapsulates multiple DSA requests along with msg code and optional acknowledgement code.
 */
case class RequestMessage(msg: Int, ack: Option[Int] = None, requests: List[DSARequest] = Nil)
    extends DSAMessage {

  /**
   * Outputs only the first request for compact logging.
   */
  override def toString = if (requests.size < 2)
    s"RequestMessage($msg,$ack,$requests)"
  else
    s"RequestMessage($msg,$ack,List(${requests.head},...${requests.size - 1} more))"
}

/**
 * Encapsulates multiple DSA responses along with msg code and optional acknowledgement code.
 */
case class ResponseMessage(msg: Int, ack: Option[Int] = None, responses: List[DSAResponse] = Nil)
    extends DSAMessage {

  /**
   * Outputs only the first response for compact logging.
   */
  override def toString = if (responses.size < 2)
    s"ResponseMessage($msg,$ack,$responses)"
  else
    s"ResponseMessage($msg,$ack,List(${responses.head},...${responses.size - 1} more))"
}