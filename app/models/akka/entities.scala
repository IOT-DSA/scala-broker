package models.akka

import controllers.ConnectionRequest

/**
 * Encapsulates DSLink information for WebSocket connection.
 */
case class ConnectionInfo(dsId: String, linkName: String, isRequester: Boolean, isResponder: Boolean,
                          linkData: Option[String] = None, version: String = "",
                          formats: List[String] = Nil, compression: Boolean = false)

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
 * Similar to [[java.util.concurrent.atomic.AtomicInteger]], but not thread safe,
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