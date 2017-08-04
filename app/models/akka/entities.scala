package models.akka

import controllers.ConnectionRequest

/**
 * Encapsulates DSLink information for WebSocket connection.
 */
case class ConnectionInfo(dsId: String, linkName: String, isRequester: Boolean, isResponder: Boolean,
                          linkData: Option[String] = None, version: String = "",
                          formats: List[String] = Nil, compression: Boolean = false) {
  val mode = (isRequester, isResponder) match {
    case (true, true)  => DSLinkMode.Dual
    case (true, false) => DSLinkMode.Requester
    case (false, true) => DSLinkMode.Responder
    case _             => throw new IllegalArgumentException("DSLink must be Requester, Responder or Dual")
  }
}

/**
 * Factory for [[ConnectionInfo]] instances.
 */
object ConnectionInfo {
  /**
   * Creates a new [[ConnectionInfo]] instance by extracting information from a connection request.
   */
  def apply(dsId: String, cr: ConnectionRequest): ConnectionInfo =
    new ConnectionInfo(dsId, dsId.substring(0, dsId.length - 44), cr.isRequester, cr.isResponder,
      cr.linkData.map(_.toString), cr.version, cr.formats.getOrElse(Nil), cr.enableWebSocketCompression)
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
}

/**
 * An envelope for message routing, that provides the entityId for the shard coordinator.
 */
final case class EntityEnvelope(entityId: String, msg: Any)

/**
 * DSA Link mode.
 */
object DSLinkMode extends Enumeration {
  type DSLinkMode = Value
  val Responder, Requester, Dual = Value
}