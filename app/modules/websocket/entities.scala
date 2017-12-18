package modules.websocket

import models.akka.ConnectionInfo
import play.api.libs.json.JsValue

/**
 * DSA Client-Broker connection request.
 */
case class ConnectionRequest(publicKey: String, isRequester: Boolean, isResponder: Boolean,
                             linkData: Option[JsValue], version: String, formats: Option[List[String]],
                             enableWebSocketCompression: Boolean)

/**
 * DSLink session info.
 */
case class DSLinkSessionInfo(ci: ConnectionInfo, sessionId: String)