package models

import play.api.libs.json.JsValue

/**
 * DSA Client-Broker connection request.
 */
case class ConnectionRequest(publicKey: String, isRequester: Boolean, isResponder: Boolean,
                             linkData: Option[JsValue], version: String, formats: List[String],
                             enableWebSocketCompression: Boolean)