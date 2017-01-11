package models

/**
 * Encapsulates multiple DSA requests along with msg code and optional acknowledgement code.
 */
case class RequestMessage(msg: Int, ack: Option[Int] = None, requests: Option[List[DSARequest]] = None)

/**
 * Encapsulates multiple DSA responses along with msg code and optional acknowledgement code.
 */
case class ResponseMessage(msg: Int, ack: Option[Int] = None, responses: Option[List[DSAResponse]] = None)