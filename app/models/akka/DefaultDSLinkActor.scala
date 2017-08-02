package models.akka

import akka.actor.Props
import models.akka.responder.PooledResponderBehavior

/**
 * Represents a DSLink endpoint, which may or may not be connected to an Endpoint actor.
 * The Endpoint actor is supplied by the facade and can represent a WebSocket or TCP connection,
 * HTTP response stream, a test actor etc.
 *
 * The facade initiates a session by sending `ConnectEndpoint` message to the actor. The session
 * ends either when `DisconnectEndpoint` message is sent to an actor, or the endpoint actor terminates.
 */
class DefaultDSLinkActor(dslinkMgr: DSLinkManager) extends AbstractDSLinkActor
    with RequesterBehavior with PooledResponderBehavior {

  /**
   * Performs post-stop actions on the requester.
   */
  override def postStop() = {
    stopRequester
    super.postStop
  }

  /**
   * Handles messages in CONNECTED state.
   */
  override def connected = super.connected orElse requesterBehavior orElse responderBehavior

  /**
   * Sends a message to an actor using its DSA link path.
   */
  def dsaSend(to: String, msg: Any) = dslinkMgr.dsaSend(to, msg)
}

/**
 * Factory for [[DefaultDSLinkActor]] instances.
 */
object DefaultDSLinkActor {
  /**
   * Creates a new [[DefaultDSLinkActor]] instance.
   */
  def props(dslinkMgr: DSLinkManager) = Props(new DefaultDSLinkActor(dslinkMgr))
}