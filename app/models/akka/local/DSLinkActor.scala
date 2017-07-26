package models.akka.local

import org.joda.time.DateTime

import akka.actor._
import models.Settings
import models.akka.ConnectionInfo
import models.akka.Messages._

/**
 * Represents a DSLink endpoint, which may or may not be connected to a WebSocket.
 */
abstract class DSLinkActor(connInfo: ConnectionInfo) extends Actor with Stash with ActorLogging {

  val linkName = self.path.name
  val linkPath = Settings.Paths.Downstream + "/" + linkName

  protected val ownId = s"DSLink[$linkName]"

  private var _ws: Option[ActorRef] = None
  protected def ws = _ws
  protected def isConnected = ws.isDefined

  private var lastConnected: Option[DateTime] = _
  private var lastDisconnected: Option[DateTime] = _

  override def preStart() = {
    log.info(s"$ownId: initialized, not connected to Endpoint")
  }

  override def postStop() = {
    log.info(s"$ownId: stopped")
  }

  /**
   * Handles incoming messages.
   */
  final def receive = disconnected

  /**
   * Handles messages in CONNECTED state.
   */
  def connected: Receive = {
    case DisconnectEndpoint(kill) =>
      log.info(s"$ownId: disconnected from Endpoint")
      disconnectFromEndpoint(kill)
    case Terminated(wsActor) =>
      log.info(s"$ownId: Endpoint terminated")
      disconnectFromEndpoint(false)
    case GetLinkInfo =>
      sender ! LinkInfo(connInfo, true, lastConnected, lastDisconnected)
  }

  /**
   * Handles messages in DISCONNECTED state.
   */
  def disconnected: Receive = {
    case ConnectEndpoint(ref, _) =>
      log.info(s"$ownId: connected to Endpoint")
      connectToEndpoint(ref)
    case GetLinkInfo =>
      sender ! LinkInfo(connInfo, false, lastConnected, lastDisconnected)
    case _ =>
      log.debug("$ownId: stashing the incoming message")
      stash()
  }

  private def connectToEndpoint(ref: ActorRef) = {
    _ws = Some(context.watch(ref))
    log.debug("$ownId: unstashing all stored messages")
    lastConnected = Some(DateTime.now)
    unstashAll()
    context.become(connected)
  }

  private def disconnectFromEndpoint(kill: Boolean) = {
    _ws foreach { ref =>
      context unwatch ref
      if (kill)
        ref ! PoisonPill
    }
    _ws = None
    lastDisconnected = Some(DateTime.now)
    context.become(disconnected)
  }
}