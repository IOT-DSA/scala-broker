package models.akka

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, actorRef2Scala }
import models.{ RequestEnvelope, ResponseEnvelope }
import models.rpc._

/**
 * Encapsulates WebSocket actor configuration.
 */
case class WSActorConfig(linkName: String, salt: Int)

/**
 * WebSocket endpoint actor for requesters, responders and dual links.
 */
class WSActor(out: ActorRef, link: ActorRef, config: WSActorConfig) extends Actor with ActorLogging {

  private val ownId = s"WSActor[${config.linkName}]"

  private var localMsgId = new IntCounter(1)

  /**
   * Sends handshake to the client and notifies the DSLink actor.
   */
  override def preStart() = {
    log.info(s"$ownId: initialized, sending 'allowed' to client")
    sendAllowed(config.salt)
    link ! DSLinkActor.WSConnected
  }

  /**
   * Cleans up after the actor stops.
   */
  override def postStop() = {
    log.info(s"$ownId: stopped")
  }

  /**
   * Handles incoming message from the client.
   */
  def receive = {
    case EmptyMessage =>
      log.debug(s"$ownId: received empty message from WebSocket, ignoring...")
    case PingMessage(msg, ack) =>
      log.debug(s"$ownId: received ping from WebSocket with msg=$msg, acking...")
      sendAck(msg)
    case m @ RequestMessage(msg, _, _) =>
      log.info(s"$ownId: received $m from WebSocket")
      sendAck(msg)
      link ! m
    case m @ ResponseMessage(msg, _, _) =>
      log.info(s"$ownId: received $m from WebSocket")
      sendAck(msg)
      link ! m
    case e @ RequestEnvelope(requests) =>
      log.debug(s"$ownId: received $e")
      sendRequests(requests: _*)
    case e @ ResponseEnvelope(responses) =>
      log.debug(s"$ownId: received $e")
      sendResponses(responses: _*)
  }

  /**
   * Sends the response message to the client.
   */
  protected def sendResponses(responses: DSAResponse*) = if (!responses.isEmpty)
    sendToSocket(ResponseMessage(localMsgId.inc, None, responses.toList))

  /**
   * Sends the request message back to the client.
   */
  protected def sendRequests(requests: DSARequest*) = if (!requests.isEmpty)
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
    log.debug(s"$ownId: sending $msg to WebSocket")
    out ! msg
  }
}

/**
 * Factory for [[WSActor]] instances.
 */
object WSActor {
  /**
   * Creates a new Props instance for [[WSActor]].
   */
  def props(out: ActorRef, link: ActorRef, config: WSActorConfig) = Props(new WSActor(out, link, config))
}