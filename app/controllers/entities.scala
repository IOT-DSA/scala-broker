package controllers

import play.api.libs.json.JsValue

/**
 * DSA Client-Broker connection request.
 */
case class ConnectionRequest(publicKey: String, isRequester: Boolean, isResponder: Boolean,
                             linkData: Option[JsValue], version: String, formats: Option[List[String]],
                             enableWebSocketCompression: Boolean)

/**
 * Object passed to a view to supply various page information.
 */
case class ViewConfig(title: String, navItem: String, heading: String, description: String,
                      downCount: Option[Int] = None, upCount: Option[Int] = None)
