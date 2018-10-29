package facades.websocket

import java.util.UUID

import akka.NotUsed
import akka.actor.Status.{Failure, Success}
import org.joda.time.DateTime
import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}
import akka.dispatch.{BoundedMessageQueueSemantics, RequiresMessageQueue}
import akka.routing.Routee
import akka.stream.{InvalidSequenceNumberException, Materializer, SinkRef, scaladsl}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, StreamRefs}
import kamon.Kamon
import models.Settings.WebSocket.OnOverflow
import models.{RequestEnvelope, ResponseEnvelope, formatMessage}
import models.akka.{ConnectionInfo, IntCounter, RichRoutee}
import models.akka.Messages.{ConnectEndpoint, GetOrCreateDSLink}
import models.metrics.Meter
import models.rpc._
import akka.pattern.ask

import scala.concurrent.Future
import java.util.concurrent.{TimeUnit => TU}

import models.akka.QoSState.SubscriptionSourceMessage

/**
  * Encapsulates WebSocket actor configuration.
  */
case class WebSocketActorConfig(connInfo: ConnectionInfo, sessionId: String, salt: Int)

case class StreamRequest()

case class RouteeUpdateRequest()

/**
  * Represents a WebSocket connection and communicates to the DSLink actor.
  */
class WebSocketActor(registry: ActorRef, config: WebSocketActorConfig)(implicit mat: Materializer)
  extends Actor with ActorLogging with RequiresMessageQueue[BoundedMessageQueueSemantics]
    with Meter {

  protected val ci = config.connInfo

  implicit val ctx = context.dispatcher

  implicit val timeout = akka.util.Timeout(10, TU.SECONDS)
  protected val linkName = ci.linkName
  protected val ownId = s"WSActor[$linkName]-${UUID.randomUUID.toString}"
  private val localMsgId = IntCounter(1)
  private val startTime = DateTime.now
  var routee: Future[Routee] = updateRoutee()


  val sink = Sink.asPublisher[DSAMessage](false)
  val (out, publisher) = Source.queue[DSAMessage](100, OnOverflow).toMat(sink)(Keep.both).run()


  /**
    * Sends handshake to the client and notifies the DSLink actor.
    */
  override def preStart() = {
    log.info("{}: initialized, sending 'allowed' to client", ownId)
    sendAllowed(config.salt)
    for (_ <- updateRoutee()) {
      routee.foreach {
        _ ! ConnectEndpoint(self, ci)
      }
      incrementTags(tagsForConnection("connected")(ci): _*)
    }
  }

  /**
    * Sends 'allowed' message to the client.
    */
  private def sendAllowed(salt: Int) = sendToSocket(AllowedMessage(true, salt))

  /**
    * Sends a DSAMessage to a WebSocket connection.
    */
  private def sendToSocket(msg: DSAMessage) = {
    log.info("{}: sending {} to WebSocket {}", ownId, formatMessage(msg), out)
    out.offer(msg)
  }

  private def updateRoutee(): Future[Routee] = {
    (registry ? GetOrCreateDSLink(config.connInfo.linkName)).mapTo[Routee]
  }

  /**
    * Cleans up after the actor stops and logs session data.
    */
  override def postStop() = {
    log.info("{}: stopped", ownId)
    decrementTags(tagsForConnection("connected")(ci): _*)
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
    case m@RequestMessage(msg, _, _) =>
      Kamon.currentSpan().tag("type", "request")
      log.debug("{}: received request message {} from WebSocket", ownId, formatMessage(m))
      sendAck(msg)
      routee.foreach {
        _ ! m
      }
      countTags(messageTags("request.in", ci): _*)
    case m@ResponseMessage(msg, _, _) =>
      Kamon.currentSpan().tag("type", "response")
      log.debug("{}: received response message {} from WebSocket", ownId, formatMessage(m))
      sendAck(msg)
      routee.foreach {
        _ ! m
      }
      countTags(messageTags("response.in", ci): _*)
    case e@RequestEnvelope(requests) =>
      log.debug("{}: received request envelope {}", ownId, e)
      sendRequests(requests: _*)
    case e@ResponseEnvelope(responses) =>
      log.debug("{}: received response envelope {}", ownId, e)
      sendResponses(responses: _*)
    case StreamRequest() =>
      sender ! createWSFlow()
    case RouteeUpdateRequest => routee = updateRoutee()
    case Success(_) | Failure(_) => self ! PoisonPill
    case Terminated(_) => context.stop(self)
  }

  /**
    * Sends the response message to the client.
    */
  private def sendResponses(responses: DSAResponse*) = if (!responses.isEmpty) {
    val msg = ResponseMessage(localMsgId.inc, None, responses.toList)
    sendToSocket(msg)
    countTags(messageTags("response.out", ci): _*)
  }

  /**
    * Sends the request message back to the client.
    */
  private def sendRequests(requests: DSARequest*) = if (!requests.isEmpty) {
    val msg = RequestMessage(localMsgId.inc, None, requests.toList)
    sendToSocket(msg)
    countTags(messageTags("request.out", ci): _*)
  }

  /**
    * Sends an ACK back to the client.
    */
  private def sendAck(remoteMsgId: Int) = sendToSocket(PongMessage(remoteMsgId))

  /**
    * Create/connect to new/existing DSLInkActor
    */
  private def createWSFlow(): Flow[DSAMessage, DSAMessage, NotUsed] = {

    val (subscriptionsPusher, subscriptionsPublisher) = StreamRefs.sinkRef[ResponseMessage]()
      .toMat(Sink.asPublisher(false))(Keep.both)
      .run()


    val subscriptionSrcRef = Source.fromPublisher(subscriptionsPublisher)
    val messageSink = Flow[Any].async.to(Sink.actorRef(self, Success(())))
    val src = Source.fromPublisher(publisher).merge(subscriptionSrcRef)


    for {
      dslinkActor <- routee
      subscriptionSink <- subscriptionsPusher
    } {
      dslinkActor ! SubscriptionSourceMessage(subscriptionSink)
    }

    Flow.fromSinkAndSource[DSAMessage, DSAMessage](messageSink, src).recover {
      case e: InvalidSequenceNumberException =>
        log.error(s"messages been lost: ${e.msg}", e)
        EmptyMessage
    }

  }


}

/**
  * Factory for [[WebSocketActor]] instances.
  */
object WebSocketActor {
  /**
    * Creates a new [[WebSocketActor]] props.
    */
  def props(registry: ActorRef, config: WebSocketActorConfig)(implicit mat: Materializer) =
    Props(new WebSocketActor(registry, config))
}