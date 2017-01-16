package models.actors

import models.{ DSARequest, DSAResponse }

/**
 * Encapsulates DSLink information for WebSocket connection.
 */
case class ConnectionInfo(dsId: String, isRequester: Boolean, isResponder: Boolean, linkPath: String)

/**
 * Envelope for internal request routing.
 */
case class RequestEnvelope(request: DSARequest)

/**
 * Envelope for internal response routing.
 */
case class ResponseEnvelope(response: DSAResponse)