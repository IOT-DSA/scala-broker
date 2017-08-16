package models.akka

import org.joda.time.DateTime

import Messages.ConnectEndpoint
import akka.actor.{ Actor, ActorLogging, ActorRef, Props, actorRef2Scala }
import models.{ RequestEnvelope, ResponseEnvelope }
import models.metrics.MetricLogger
import models.rpc._

/**
 * Encapsulates WebSocket actor configuration.
 */
case class WebSocketActorConfig(connInfo: ConnectionInfo, salt: Int)

/**
 * Represents a WebSocket connection and communicates to the DSLink actor.
 */
class WebSocketActor(out: ActorRef, proxy: CommProxy, config: WebSocketActorConfig) extends Actor with ActorLogging {

  protected val ci = config.connInfo
  protected val linkName = ci.linkName
  protected val ownId = s"WSActor[$linkName]"

  private var localMsgId = new IntCounter(1)

  private val startTime = DateTime.now

  /**
   * Sends handshake to the client and notifies the DSLink actor.
   */
  override def preStart() = {
    log.info("{}: initialized, sending 'allowed' to client", ownId)
    sendAllowed(config.salt)
    proxy tell ConnectEndpoint(self, ci)
  }

  /**
   * Cleans up after the actor stops and logs session data.
   */
  override def postStop() = {
    log.info("{}: stopped", ownId)
    MetricLogger.logWebSocketSession(startTime, DateTime.now, linkName, ci.linkAddress, ci.mode, ci.brokerAddress)
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
      proxy tell m
      MetricLogger.logRequestMessage(DateTime.now, linkName, ci.linkAddress, m)
    case m @ ResponseMessage(msg, _, _) =>
      log.info("{}: received {} from WebSocket", ownId, formatMessage(m))
      sendAck(msg)
      proxy tell m
      MetricLogger.logResponseMessage(DateTime.now, linkName, ci.linkAddress, m)
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
  private def sendResponses(responses: DSAResponse*) = if (!responses.isEmpty)
    sendToSocket(ResponseMessage(localMsgId.inc, None, responses.toList))

  /**
   * Sends the request message back to the client.
   */
  private def sendRequests(requests: DSARequest*) = if (!requests.isEmpty)
    sendToSocket(RequestMessage(localMsgId.inc, None, requests.toList))

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
  def props(out: ActorRef, proxy: CommProxy, config: WebSocketActorConfig) =
    Props(new WebSocketActor(out, proxy, config))
}