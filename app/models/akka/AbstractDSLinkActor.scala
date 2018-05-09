package models.akka

import org.joda.time.DateTime
import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Stash, Terminated, actorRef2Scala}
import akka.persistence.PersistentActor
import akka.routing.Routee

/**
 * Represents a DSLink endpoint, which may or may not be connected to an Endpoint.
 * The Endpoint actor is supplied by the facade and can represent a WebSocket or TCP connection,
 * HTTP response stream, a test actor etc.
 *
 * This actor can represent either a downlink (a dslink connection) or uplink (upstream broker connection).
 * The `registry` passed in the constructor is used to send notifications about the actor's state changes.
 *
 * Initially the actor is in `disconnected` state. The facade initiates a session by sending `ConnectEndpoint`
 * message to the actor. The session ends either when `DisconnectEndpoint` message is sent to an actor,
 * or the endpoint actor terminates.
 * 
 * When the actor is disconnected, it stashes incoming messages and releases them to the endpoint, once it
 * becomes connected again.
 */
abstract class AbstractDSLinkActor(registry: Routee) extends PersistentActor with Stash with ActorLogging {
  import Messages._

  protected val linkName = self.path.name
  protected val ownId = s"DSLink[$linkName]"

  // initially None, then set by ConnectEndpoint, unset by DisconnectEndpoint
  private var endpoint: Option[ActorRef] = None

  // initially an empty one, then set by ConnectEndpoint; can be changed by another ConnectEndpoint, is a requester by initially
  protected var connInfo = ConnectionInfo("", linkName, true, false)

  private var lastConnected: Option[DateTime] = None
  private var lastDisconnected: Option[DateTime] = None

  override def persistenceId = linkName

  private def updateState(event: DSLinkState) = {
    endpoint = event.endpoint
    connInfo = event.connInfo
    lastConnected = event.lastConnected.collect { case x => new DateTime(x) }
    lastDisconnected = event.lastDisconnected.collect { case x => new DateTime(x) }
    log.debug(s"$ownId: state has become [endpoint: $endpoint] [connInfo: $connInfo] [lastConnected: $lastConnected] [lastDisconnected: $lastDisconnected]")
  }

  /**
   * Called on link start up: notifies the registry and logs the dslink status.
   */
  override def preStart() = {
    sendToRegistry(RegisterDSLink(linkName, connInfo.mode, false))
    log.info(s"$ownId: initialized, not connected to Endpoint")
  }

  /**
   * Called on link shut down, notifies the registry and logs the dslink status.
   */
  override def postStop() = {
    sendToRegistry(UnregisterDSLink(self.path.name))
    log.info(s"$ownId: stopped")
  }

  /**
    * Recovers an event from the journal.
    */
  override def receiveRecover: Receive = {
    case event: DSLinkState =>
      log.debug(s"$ownId: trying to recover $event")
      updateState(event)
//    case Snapshot(_, snapshot: DSLinkState) => state = snapshot
    case _ =>
      log.warning(s"$ownId: not supported event")
  }

  /**
   * Handles incoming messages, starting in DISCONNECTED state.
   */
  override def receiveCommand: Receive = disconnected

  /**
   * Handles messages in CONNECTED state.
   */
  def connected: Receive = {
    case DisconnectEndpoint(kill) =>
      log.info(s"$ownId: disconnected from Endpoint")
      disconnectFromEndpoint(kill)
    case Terminated(wsActor) =>
      log.info(s"$ownId: Endpoint terminated, disconnecting")
      disconnectFromEndpoint(false)
    case GetLinkInfo =>
      log.debug(s"$ownId: LinkInfo requested, dispatching")
      sender ! LinkInfo(connInfo, true, lastConnected, lastDisconnected)
    case ConnectEndpoint(ref, ci) =>
      log.warning(s"$ownId: already connected to Endpoint, dropping previous association")
      disconnectFromEndpoint(true)
      connectToEndpoint(ref, ci)
  }

  /**
   * Handles messages in DISCONNECTED state.
   */
  private def disconnected: Receive = {
    case ConnectEndpoint(ref, ci) =>
      log.info(s"$ownId: connected to Endpoint")
      connectToEndpoint(ref, ci)
    case GetLinkInfo =>
      log.debug(s"$ownId: LinkInfo requested, dispatching")
      sender ! LinkInfo(connInfo, false, lastConnected, lastDisconnected)
    case DisconnectEndpoint(_) =>
      log.warning(s"$ownId: not connected to Endpoint, ignoring DISCONNECT")
    case _ =>
      log.debug(s"$ownId: stashing the incoming message")
      stash()
  }

  /**
   * Associates this DSLink with an endpoint.
   */
  private def connectToEndpoint(ref: ActorRef, ci: ConnectionInfo) = {
    log.debug(s"$ownId: connectToEndpoint called, [ref: $ref] [connection: $ci]")
    assert(ci.isRequester || ci.isResponder, "DSLink must be Requester, Responder or Dual")

    persist(DSLinkState(Some(context.watch(ref)), ci, Some(DateTime.now.toDate), lastDisconnected.collect { case x => x.toDate } )) { event =>
      updateState(event)
      sendToRegistry(DSLinkStateChanged(linkName, ci.mode, true))

      log.debug(s"$ownId: unstashing all stored messages")
      unstashAll()
      context.become(connected)
    }
  }

  /**
   * Dissociates this DSLink from the endpoint.
   */
  private def disconnectFromEndpoint(kill: Boolean) = {
    log.debug(s"$ownId: disconnectFromEndpoint called, [kill: $kill]")

    endpoint foreach { ref =>
      context unwatch ref
      if (kill) ref ! PoisonPill
    }

    persist(DSLinkState(None, connInfo, lastConnected.collect { case x => x.toDate }, Some(DateTime.now.toDate))) { event =>
      updateState(event)
      sendToRegistry(DSLinkStateChanged(linkName, connInfo.mode, false))
      context.become(disconnected)
    }
  }

  /**
   * Sends a message to the endpoint, if connected.
   */
  protected def sendToEndpoint(msg: Any): Unit = endpoint foreach (_ ! msg)

  /**
   * Sends a message to the registry.
   */
  protected def sendToRegistry(msg: Any): Unit = registry ! msg
}
