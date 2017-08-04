package models.akka

import org.joda.time.DateTime

import akka.actor.{ Actor, ActorLogging, ActorRef, PoisonPill, Stash, Terminated, actorRef2Scala }
import models.Settings

/**
 * Represents a DSLink endpoint, which may or may not be connected to an Endpoint.
 */
abstract class AbstractDSLinkActor extends Actor with Stash with ActorLogging {
  import Messages._

  protected val linkName = self.path.name
  protected val linkPath = Settings.Paths.Downstream + "/" + linkName
  protected val ownId = s"DSLink[$linkName]"

  // initially None, then set by ConnectEndpoint, unset by DisconnectEndpoint
  private var endpoint: Option[ActorRef] = None

  // initially an empty one, then set by ConnectEndpoint; can be changed by another ConnectEndpoint
  private var connInfo = ConnectionInfo("", linkName, true, false)

  private var registered: Boolean = false
  private var lastConnected: Option[DateTime] = None
  private var lastDisconnected: Option[DateTime] = None

  /**
   * Called on link start up, logs the dslink status.
   */
  override def preStart() = log.info(s"$ownId: initialized, not connected to Endpoint")

  /**
   * Called on link shut down, unregisters from Backend and logs the dslink status.
   */
  override def postStop() = {
    if (registered) sendToBackend(BackendActor.UnregisterDSLink(self.path.name))
    log.info(s"$ownId: stopped")
  }

  /**
   * Handles incoming messages, starting in DISCONNECTED state.
   */
  def receive = disconnected

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
  def disconnected: Receive = {
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
    assert(ci.isRequester || ci.isResponder, "DSLink must be Requester, Responder or Dual")

    endpoint = Some(context.watch(ref))
    connInfo = ci
    lastConnected = Some(DateTime.now)

    if (registered)
      sendToBackend(BackendActor.DSLinkStateChanged(linkName, ci.mode, true))
    else {
      sendToBackend(BackendActor.RegisterDSLink(linkName, ci.mode, true))
      registered = true
    }

    log.debug(s"$ownId: unstashing all stored messages")
    unstashAll()
    context.become(connected)
  }

  /**
   * Disassociates this DSLink from the endpoint.
   */
  private def disconnectFromEndpoint(kill: Boolean) = {
    endpoint foreach { ref =>
      context unwatch ref
      if (kill) ref ! PoisonPill
    }
    endpoint = None
    lastDisconnected = Some(DateTime.now)

    sendToBackend(BackendActor.DSLinkStateChanged(linkName, connInfo.mode, false))

    context.become(disconnected)
  }

  /**
   * Sends a message to the endpoint, if connected.
   */
  protected def sendToEndpoint(msg: Any): Unit = endpoint foreach (_ ! msg)

  /**
   * Sends a message to the backend actor.
   */
  protected def sendToBackend(msg: Any): Unit = context.actorSelection("/user/backend") ! msg
}