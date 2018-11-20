package models.akka

import org.joda.time.DateTime
import akka.persistence._
import akka.actor.{ActorLogging, ActorRef, PoisonPill, Stash, Terminated, actorRef2Scala}
import akka.remote.Ack
import akka.routing.Routee
import facades.websocket.RouteeUpdateRequest
import models.metrics.Meter
import models.rpc.PingMessage
import models.util.DsaToAkkaCoder._
import play.http.websocket.Message.Pong

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
abstract class AbstractDSLinkActor(routeeRegistry: Routee) extends PersistentActor with DSLinkStateSnapshotter with Stash  with ActorLogging with
  Meter {

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

  var afterConnection: Seq[ActorRef => Unit] = Seq()

  /**
    * Called on link start up: notifies the registry and logs the dslink status.
    */
  override def preStart() = {
    connInfo = ConnectionInfo("", linkName, true, false)
    sendToRegistry(RegisterDSLink(linkName, connInfo.mode, false))

    if(endpoint.isEmpty){
      log.info("{}: initialized, not connected to Endpoint", ownId)
    } else {
      log.info("{}: initialized connected to Endpoint {}", ownId, endpoint)
    }

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
      endpoint.foreach(endP =>
        afterConnection.foreach(_ (endP))
      )

    case RecoveryCompleted =>
      afterRecovery
  }

  def afterRecovery(): Unit = {
    endpoint.foreach { ep =>
      ep ! RouteeUpdateRequest()
      log.info("{}: Recovery done, connected to {}", ownId, endpoint)
      log.debug("{}: unstashing all stored messages", ownId)
      context.become(connected orElse snapshotReceiver)
    }
    unstashAll()
    log.info("{}: recovery completed with persistenceId: '{}'", ownId, persistenceId)
  }

  private def updateState(event: DSLinkBaseState) = {
    endpoint = event.endpoint
    connInfo = event.connInfo
    lastConnected = event.lastConnected.map {
      new DateTime(_)
    }
    lastDisconnected = event.lastDisconnected.map {
      new DateTime(_)
    }
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
      endpoint.foreach(disconnectFromEndpoint(_, kill))
    case Terminated(wsActor) =>
      if (Some(wsActor) == endpoint) {
        log.info("{}: Endpoint terminated, disconnecting, actor: {}", ownId, wsActor)
        disconnectFromEndpoint(wsActor, false)
      }
    case GetLinkInfo =>
      log.debug("{}: LinkInfo requested, dispatching", ownId)
      sender ! LinkInfo(connInfo, true, lastConnected, lastDisconnected)
    case ConnectEndpoint(ci, wsActor) =>
      if(Some(wsActor) != endpoint){
        log.warning("{}: already connected to Endpoint, dropping previous association", ownId)
        endpoint.foreach(disconnectFromEndpoint(_, true))
        connectToEndpoint(wsActor, ci)
        wsActor ! s"Reconnecting, Prev connection ${endpoint} will be disconnected"
      } else {
        wsActor ! "Already connected"
      }
    case m: PingMessage => sender ! "Ok"

  }

  /**
    * Handles messages in DISCONNECTED state.
    */
  def disconnected: Receive = {
    case ConnectEndpoint(ci, wsActor) =>
      connectToEndpoint(wsActor, ci)
      log.info("{}: connected to Endpoint {}", ownId, wsActor)
      wsActor ! s"Connecting"
    case GetLinkInfo =>
      log.debug("{}: LinkInfo requested, dispatching", ownId)
      sender ! LinkInfo(connInfo, false, lastConnected, lastDisconnected)
    case DisconnectEndpoint(_) =>
      log.warning("{}: not connected to Endpoint, ignoring DISCONNECT", ownId)
  }

  def toStash: Receive = {
    case m =>
      log.debug("{}: stashing the incoming message: {}", ownId, m)
      stash()
  }

  /**
    * Associates this DSLink with an endpoint.
    */
  private def connectToEndpoint(ref: ActorRef, ci: ConnectionInfo) = {
    log.info("{}: connectToEndpoint called, [ref: {}] [connection: {}]", ownId, ref, ci)
    assert(ci.isRequester || ci.isResponder, "DSLink must be Requester, Responder or Dual")

    persist(DSLinkBaseState(Some(ref), ci, Some(DateTime.now.toDate), lastDisconnected.map {
      _.toDate
    })) { event =>
      context.watch(ref)
      log.debug("{}: persisting {}", ownId, event)
      updateState(event)
      saveSnapshot(event)
      sendToRegistry(DSLinkStateChanged(linkName, event.connInfo.mode, true))

      log.debug("{}: unstashing all stored messages", ownId)
      unstashAll()
      afterConnection.foreach(_ (ref))
      context.become(connected orElse snapshotReceiver)
    }
  }

  /**
    * Dissociates this DSLink from the endpoint.
    */
  private def disconnectFromEndpoint(endPoint: ActorRef, kill: Boolean) = {
    log.debug("{}: disconnectFromEndpoint called, [kill: {}]", ownId, kill)
    endpoint foreach { ref =>
      context unwatch ref
      if (kill) ref ! PoisonPill
    }

    persist(DSLinkBaseState(None, connInfo, lastConnected.map {
      _.toDate
    }, Some(DateTime.now.toDate))) { event =>
      log.debug("{}: persisting {}", ownId, event)
      updateState(event)
      saveSnapshot(event)

      sendToRegistry(DSLinkStateChanged(linkName, event.connInfo.mode, false))
      context.become(disconnected orElse snapshotReceiver)
    }
  }

  /**
    * Sends a message to the endpoint, if connected.
    */
  protected def sendToEndpoint(msg: Any): Unit = endpoint foreach (_ ! msg)

  /**
    * Sends a message to the registry.
    */
  protected def sendToRegistry(msg: Any): Unit = routeeRegistry ! msg
}