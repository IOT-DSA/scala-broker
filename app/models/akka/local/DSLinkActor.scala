package models.akka.local

import akka.actor.Props
import models.Settings
import models.akka.{ AbstractDSLinkActor, RequesterBehavior, PooledResponderBehavior }

/**
 * Represents a DSLink endpoint, which may or may not be connected to an Endpoint actor.
 * The Endpoint actor is supplied by the facade and can represent a WebSocket or TCP connection,
 * HTTP response stream, a test actor etc.
 *
 * The facade initiates a session by sending `ConnectEndpoint` message to the actor. The session
 * ends either when `DisconnectEndpoint` message is sent to an actor, or the endpoint actor terminates.
 */
class DSLinkActor extends AbstractDSLinkActor with RequesterBehavior with PooledResponderBehavior {

  /**
   * Handles messages in CONNECTED state.
   */
  override def connected = super.connected orElse requesterBehavior orElse responderBehavior

  /**
   * Cleans up all active requests by sending CLOSE and UNSUBSCRIBE messages to responders.
   */
  override def postStop() = {
    stopRequester
    super.postStop
  }

  /**
   * Sends a message to an actor using its DSA link path.
   */
  def dsaSend(to: String, msg: Any) = context.actorSelection("/user/" + Settings.Nodes.Root + to) ! msg
}

/**
 * Factory for [[DSLinkActor]] instances.
 */
object DSLinkActor {
  /**
   * Creates a new instance of [[DSLinkActor]] props.
   */
  def props = Props(new DSLinkActor)
}