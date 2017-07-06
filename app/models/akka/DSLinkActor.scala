package models.akka

import org.joda.time.DateTime

import akka.actor._
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import models.Settings
import models.rpc.DSAMessage

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
    case StartWSFlow =>
      log.debug(s"$ownId: connecting to WebSocket")
      sender ! createWSFlow()
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

  /**
   * Creates a new WebSocket flow bound to a newly created WSActor.
   */
  private def createWSFlow(bufferSize: Int = 16, overflow: OverflowStrategy = OverflowStrategy.dropNew) = {
    val (toSocket, publisher) = Source.actorRef[DSAMessage](bufferSize, overflow)
      .toMat(Sink.asPublisher(false))(Keep.both).run()

    val wsProps = WSActor.props(toSocket, self, WSActorConfig(linkName, Settings.Salt))

    val fromSocket = context.actorOf(Props(new Actor {
      val wsActor = context.watch(context.actorOf(wsProps, "wsActor"))

      def receive = {
        case Status.Success(_) | Status.Failure(_) => wsActor ! PoisonPill
        case Terminated(_)                         => context.stop(self)
        case other                                 => wsActor ! other
      }

      override def supervisorStrategy = OneForOneStrategy() {
        case _ => SupervisorStrategy.Stop
      }
    }))

    Flow.fromSinkAndSource[DSAMessage, DSAMessage](
      Sink.actorRef(fromSocket, Status.Success(())),
      Source.fromPublisher(publisher))
  }
}

/**
 * DSLinkActor messages.
 */
object DSLinkActor {

  /**
   * Sent by the controller to initiate a new WebSocket flow.
   */
  case object StartWSFlow

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