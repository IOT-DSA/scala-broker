package models.akka

import org.joda.time.DateTime

import akka.actor.{ Actor, ActorLogging, ActorRef, Stash, Terminated, actorRef2Scala }
import akka.stream.ActorMaterializer
import models.Settings

/**
 * Represents a DSLink endpoint, which may or may not be connected to a WebSocket.
 */
abstract class DSLinkActor(connInfo: ConnectionInfo) extends Actor with Stash with ActorLogging {
  import DSLinkActor._

  val linkName = self.path.name
  val linkPath = Settings.Paths.Downstream + "/" + linkName

  protected val ownId = s"DSLink[$linkName]"

  implicit protected val materializer = ActorMaterializer()

  private var _ws: Option[ActorRef] = None
  protected def ws = _ws
  protected def isConnected = ws.isDefined

  private var lastConnected: Option[DateTime] = _
  private var lastDisconnected: Option[DateTime] = _

  override def preStart() = {
    log.info(s"$ownId: initialized, not connected to WebSocket")
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
    case Terminated(wsActor) =>
      log.info(s"$ownId: disconnected from WebSocket")
      context.unwatch(wsActor)
      _ws = None
      lastDisconnected = Some(DateTime.now)
      context.become(disconnected)
    case GetLinkInfo =>
      sender ! LinkInfo(connInfo, true, lastConnected, lastDisconnected)
  }

  /**
   * Handles messages in DISCONNECTED state.
   */
  def disconnected: Receive = {
    case WSConnected =>
      log.info(s"$ownId: connected to WebSocket")
      _ws = Some(context.watch(sender))
      log.debug("$ownId: unstashing all stored messages")
      lastConnected = Some(DateTime.now)
      unstashAll()
      context.become(connected)
    case GetLinkInfo =>
      sender ! LinkInfo(connInfo, false, lastConnected, lastDisconnected)
    case _ =>
      log.debug("$ownId: stashing the incoming message")
      stash()
  }
}

/**
 * DSLinkActor messages.
 */
object DSLinkActor {

  /**
   * Sent by a WSActor once the socket connection has been established.
   */
  case object WSConnected

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