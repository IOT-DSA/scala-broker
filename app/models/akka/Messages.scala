package models.akka

import org.joda.time.DateTime

import akka.actor.ActorRef

/**
 * Common messages passed between the broker actors.
 */
object Messages {

  /**
   * It is sent by the facade to a DSLinkActor to connect it to the specified Endpoint actor (the
   * one that manages the physical connection to a remote process via WebSockets, TCP/IP, etc.
   */
  case class ConnectEndpoint(ep: ActorRef, ci: ConnectionInfo)

  /**
   * Disconnects the endpoint actor, optionally sending it a PoisonPill.
   */
  case class DisconnectEndpoint(kill: Boolean)

  /**
   * Request to send detailed link information.
   */
  case object GetLinkInfo

  /**
   * Encapsulates link information sent as the response to GetLinkInfo command.
   */
  case class LinkInfo(ci: ConnectionInfo, connected: Boolean,
                      lastConnected: Option[DateTime],
                      lastDisconnected: Option[DateTime])
}