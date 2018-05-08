package models.akka

/**
 * Encapsulates DSLink information for WebSocket connection.
 */
case class ConnectionInfo(dsId: String, linkName: String, isRequester: Boolean, isResponder: Boolean,
                          linkData: Option[String] = None, version: String = "",
                          formats: List[String] = Nil, compression: Boolean = false,
                          linkAddress: String = "", brokerAddress: String = "", resultFormat: String = "json") {
  val mode = (isRequester, isResponder) match {
    case (true, true)  => DSLinkMode.Dual
    case (true, false) => DSLinkMode.Requester
    case (false, true) => DSLinkMode.Responder
    case _             => throw new IllegalArgumentException("DSLink must be Requester, Responder or Dual")
  }
}

/**
 * Similar to java AtomicInteger, but not thread safe,
 * optimized for single threaded execution by an actor.
 */
class IntCounter(init: Int = 0) {
  private var value = init

  @inline def get = value
  
  @inline def inc = {
    val result = value
    value += 1
    result
  }
  
  @inline def inc(count: Int) = {
    val start = value
    value += count
    (start until value)
  }
}

/**
 * DSA Link mode.
 */
object DSLinkMode extends Enumeration {
  type DSLinkMode = Value
  val Responder, Requester, Dual = Value
}