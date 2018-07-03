package models.akka

import org.joda.time.DateTime
import akka.persistence._
import akka.actor.{ActorLogging, ActorRef, PoisonPill, Stash, Terminated, actorRef2Scala}
import akka.routing.Routee
import models.metrics.Meter
import models.util.DsaToAkkaCoder._

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
abstract class AbstractDSLinkActor(routeeRegistry: Routee) extends PersistentActor with DSLinkStateSnapshotter with Stash with ActorLogging with Meter {
  import Messages._

  protected val linkName = self.path.name.forDsa
  protected val ownId = s"DSLink[$linkName]"

  implicit val system = context.system

  // initially None, then set by ConnectEndpoint, unset by DisconnectEndpoint
  private var endpoint: Option[ActorRef] = None

  // initially an empty one, then set by ConnectEndpoint; can be changed by another ConnectEndpoint, is a requester by initially
  protected var connInfo = ConnectionInfo("", linkName, true, false)

  private var lastConnected: Option[DateTime] = None
  private var lastDisconnected: Option[DateTime] = None

  /**
   * Called on link start up: notifies the registry and logs the dslink status.
   */
  override def preStart() = {
    connInfo = ConnectionInfo("", linkName, true, false)
    sendToRegistry(RegisterDSLink(linkName, connInfo.mode, false))
    log.info("{}: initialized, not connected to Endpoint", ownId)
  }

  /**
   * Called on link shut down, notifies the registry and logs the dslink status.
   */
  override def postStop() = {
    sendToRegistry(UnregisterDSLink(self.path.name.forDsa))
    log.info("{}: stopped", ownId)
  }

  /**
   * Recovers events of base layer from the journal.
   */
  val recoverBaseState: Receive = {
    case event: DSLinkBaseState =>
      log.debug("{}: recovering with event/snapshot {}", ownId, event)
      updateState(event)
  }

  private def updateState(event: DSLinkBaseState) = {
    endpoint = event.endpoint
    connInfo = event.connInfo
    lastConnected = event.lastConnected.map { new DateTime(_) }
    lastDisconnected = event.lastDisconnected.map { new DateTime(_) }
  }

  /**
   * Handles incoming messages, starting in DISCONNECTED state.
   */
  override def receiveCommand: Receive = disconnected orElse snapshotReceiver

  /**
   * Handles messages in CONNECTED state.
   */
  def connected: Receive = {
    case DisconnectEndpoint(kill) =>
      log.info("{}: disconnected from Endpoint", ownId)
      disconnectFromEndpoint(kill)
    case Terminated(wsActor) =>
      log.info("{}: Endpoint terminated, disconnecting", ownId)
      disconnectFromEndpoint(false)
    case GetLinkInfo =>
      log.debug("{}: LinkInfo requested, dispatching", ownId)
      sender ! LinkInfo(connInfo, true, lastConnected, lastDisconnected)
    case ConnectEndpoint(ref, ci) =>
      log.warning("{}: already connected to Endpoint, dropping previous association", ownId)
      disconnectFromEndpoint(true)
      connectToEndpoint(ref, ci)
  }

  /**
   * Handles messages in DISCONNECTED state.
   */
  def disconnected: Receive = {
    case ConnectEndpoint(ref, ci) =>
      log.info("{}: connected to Endpoint", ownId)
      connectToEndpoint(ref, ci)
    case GetLinkInfo =>
      log.debug("{}: LinkInfo requested, dispatching", ownId)
      sender ! LinkInfo(connInfo, false, lastConnected, lastDisconnected)
    case DisconnectEndpoint(_) =>
      log.warning("{}: not connected to Endpoint, ignoring DISCONNECT", ownId)
  }

  def toStash: Receive = {
    case _ =>
      log.debug("{}: stashing the incoming message", ownId)
      stash()
  }

  protected def afterConnection():Unit = {}

  protected def afterDisconnection():Unit = {}

  /**
   * Associates this DSLink with an endpoint.
   */
  private def connectToEndpoint(ref: ActorRef, ci: ConnectionInfo) = {
    log.debug("{}: connectToEndpoint called, [ref: {}] [connection: {}]", ownId, ref, ci)
    assert(ci.isRequester || ci.isResponder, "DSLink must be Requester, Responder or Dual")

    persist(DSLinkBaseState(Some(context.watch(ref)), ci, Some(DateTime.now.toDate), lastDisconnected.map { _.toDate } )) { event =>
      log.debug("{}: persisting {}", ownId, event)
      saveSnapshot(event)

      sendToRegistry(DSLinkStateChanged(linkName, event.connInfo.mode, true))

      log.debug("{}: unstashing all stored messages", ownId)
      unstashAll()
      context.become(connected orElse snapshotReceiver)
      afterConnection()
    }
  }

  /**
   * Dissociates this DSLink from the endpoint.
   */
  private def disconnectFromEndpoint(kill: Boolean) = {
    log.debug("{}: disconnectFromEndpoint called, [kill: {}]", ownId, kill)
    endpoint foreach { ref =>
      context unwatch ref
      if (kill) ref ! PoisonPill
    }

    persist(DSLinkBaseState(None, connInfo, lastConnected.map { _.toDate }, Some(DateTime.now.toDate))) { event =>
      log.debug("{}: persisting {}", ownId, event)
      saveSnapshot(event)

      sendToRegistry(DSLinkStateChanged(linkName, event.connInfo.mode, false))
      context.become(disconnected orElse snapshotReceiver)
      afterDisconnection()
    }
  }

  /**
   * Sends a message to the endpoint, if connected.
   */
  protected def sendToEndpoint(msg: Any): Unit =  endpoint foreach (_ ! msg)

  /**
   * Sends a message to the registry.
   */
  protected def sendToRegistry(msg: Any): Unit = routeeRegistry ! msg
}