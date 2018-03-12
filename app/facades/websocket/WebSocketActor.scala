package facades.websocket

import org.joda.time.DateTime

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, actorRef2Scala }
import akka.dispatch.{ BoundedMessageQueueSemantics, RequiresMessageQueue }
import akka.routing.Routee
import models.{ RequestEnvelope, ResponseEnvelope }
import models.akka.{ ConnectionInfo, IntCounter, RichRoutee }
import models.akka.Messages.ConnectEndpoint
import models.formatMessage
import models.metrics.EventDaos
import models.rpc._

/**
 * Encapsulates WebSocket actor configuration.
 */
case class WebSocketActorConfig(connInfo: ConnectionInfo, sessionId: String, salt: Int)

/**
 * Represents a WebSocket connection and communicates to the DSLink actor.
 */
class WebSocketActor(out: ActorRef, routee: Routee, eventDaos: EventDaos, config: WebSocketActorConfig)
  extends Actor with ActorLogging with RequiresMessageQueue[BoundedMessageQueueSemantics] {

  import eventDaos._

  protected val ci = config.connInfo
  protected val linkName = ci.linkName
  protected val ownId = s"WSActor[$linkName]"

  private val localMsgId = new IntCounter(1)

  private val startTime = DateTime.now

  /**
   * Sends handshake to the client and notifies the DSLink actor.
   */
  override def preStart() = {
    log.info("{}: initialized, sending 'allowed' to client", ownId)
    sendAllowed(config.salt)
    routee ! ConnectEndpoint(self, ci)
    dslinkEventDao.saveConnectionEvent(startTime, "connect", config.sessionId, ci.dsId, linkName,
      ci.linkAddress, ci.mode, ci.version, ci.compression, ci.brokerAddress)
  }

  /**
   * Cleans up after the actor stops and logs session data.
   */
  override def postStop() = {
    log.info("{}: stopped", ownId)
    val endTime = DateTime.now
    dslinkEventDao.saveConnectionEvent(endTime, "disconnect", config.sessionId, ci.dsId, linkName,
      ci.linkAddress, ci.mode, ci.version, ci.compression, ci.brokerAddress)
    dslinkEventDao.saveSessionEvent(config.sessionId, startTime, endTime, linkName, ci.linkAddress, ci.mode,
      ci.brokerAddress)
  }

  /**
   * Handles incoming message from the client.
   */
  def receive = {
    case EmptyMessage =>
      log.debug("{}: received empty message from WebSocket, ignoring...", ownId)
    case PingMessage(msg, ack) =>
      log.debug("{}: received ping from WebSocket with msg={}, acking...", ownId, msg)
      sendAck(msg)
    case m @ RequestMessage(msg, _, _) =>
      log.info("{}: received {} from WebSocket", ownId, formatMessage(m))
      sendAck(msg)
      routee ! m
      requestEventDao.saveRequestMessageEvent(DateTime.now, true, linkName, ci.linkAddress, m)
    case m @ ResponseMessage(msg, _, _) =>
      log.info("{}: received {} from WebSocket", ownId, formatMessage(m))
      sendAck(msg)
      routee ! m
      responseEventDao.saveResponseMessageEvent(DateTime.now, true, linkName, ci.linkAddress, m)
    case e @ RequestEnvelope(requests) =>
      log.debug("{}: received {}", ownId, e)
      sendRequests(requests: _*)
    case e @ ResponseEnvelope(responses) =>
      log.debug(s"{}: received {}", ownId, e)
      sendResponses(responses: _*)
  }

  /**
   * Sends the response message to the client.
   */
  private def sendResponses(responses: DSAResponse*) = if (!responses.isEmpty) {
    val msg = ResponseMessage(localMsgId.inc, None, responses.toList)
    sendToSocket(msg)
    responseEventDao.saveResponseMessageEvent(DateTime.now, false, linkName, ci.linkAddress, msg)
  }

  /**
   * Sends the request message back to the client.
   */
  private def sendRequests(requests: DSARequest*) = if (!requests.isEmpty) {
    val msg = RequestMessage(localMsgId.inc, None, requests.toList)
    sendToSocket(msg)
    requestEventDao.saveRequestMessageEvent(DateTime.now, false, linkName, ci.linkAddress, msg)
  }

  /**
   * Sends 'allowed' message to the client.
   */
  private def sendAllowed(salt: Int) = sendToSocket(AllowedMessage(true, salt))

  /**
   * Sends an ACK back to the client.
   */
  private def sendAck(remoteMsgId: Int) = sendToSocket(PingMessage(localMsgId.inc, Some(remoteMsgId)))

  /**
   * Sends a DSAMessage to a WebSocket connection.
   */
  private def sendToSocket(msg: DSAMessage) = {
    log.debug("{}: sending {} to WebSocket", ownId, formatMessage(msg))
    out ! msg
  }
}

/**
 * Factory for [[WebSocketActor]] instances.
 */
object WebSocketActor {
  /**
   * Creates a new [[WebSocketActor]] props.
   */
  def props(out: ActorRef, routee: Routee, eventDaos: EventDaos, config: WebSocketActorConfig) =
    Props(new WebSocketActor(out, routee, eventDaos, config))
}