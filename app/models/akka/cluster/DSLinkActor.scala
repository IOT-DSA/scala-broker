package models.akka.cluster

import org.joda.time.DateTime

import akka.actor._
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import models.Settings
import models.akka.{ ConnectionInfo, EntityEnvelope }
import models.akka.Messages._
import models.akka.ShardedActorProxy

/**
 * Represents a DSLink endpoint, which may or may not be connected to an Endpoint actor.
 * The Endpoint actor is supplied by the facade and can represent a WebSocket or TCP connection,
 * HTTP response stream, a test actor etc.
 *
 * The facade initiates a session by sending `ConnectEndpoint` message to the actor. The session
 * ends either when `DisconnectEndpoint` message is sent to an actor, or the endpoint actor terminates.
 */
class DSLinkActor extends Actor with Stash with ActorLogging with RequesterBehavior with ResponderBehavior {
  import DSLinkActor._
  import Settings.Paths._

  protected def linkName = self.path.name
  protected val linkPath = Settings.Paths.Downstream + "/" + linkName
  protected val ownId = s"DSLink[$linkName]"

  private var endpoint: Option[ActorRef] = None
  private var connInfo: ConnectionInfo = _
  private var lastConnected: Option[DateTime] = None
  private var lastDisconnected: Option[DateTime] = None

  override def preStart() = {
    context.actorSelection("/user/backend") ! BackendActor.RegisterDSLink
    log.info(s"$ownId: initialized, not connected to Endpoint")
  }

  override def postStop() = {
    stopRequester
    context.actorSelection("/user/backend") ! BackendActor.UnregisterDSLink
    log.info(s"$ownId: stopped")
  }

  /**
   * Handles incoming messages.
   */
  final def receive = disconnected

  /**
   * Handles messages in CONNECTED state.
   */
  def connected = linkConnected orElse requesterBehavior orElse responderBehavior

  /**
   * Manages basic DSLink connected state.
   */
  def linkConnected: Receive = {
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
      if (kill)
        ref ! PoisonPill
    }
    endpoint = None
    lastDisconnected = Some(DateTime.now)
    context.become(disconnected)
  }

  /**
   * Sends a message to the endpoint, if connected.
   */
  protected def sendToEndpoint(msg: Any) = endpoint foreach (_ ! msg)
  
  /**
   * Sends a message to an actor using its DSA link path.
   */
  def dsaSend(to: String, msg: Any) = {
    if (to == Downstream)
      context.actorSelection("/user/backend") ! msg
    else if (to startsWith Downstream) {
      val linkName = to drop Downstream.size + 1
      implicit val system = context.system
      new ShardedActorProxy(DSLinkActor.region, linkName) ! msg
    }
    else {
      context.actorSelection("/user/" + Settings.Nodes.Root + to) ! msg
    }
  }  
}

/**
 * Factory for [[DSLinkActor]] instances and defined messages.
 */
object DSLinkActor {
  import models.Settings._

  /**
   * Creates a new instance of [[DSLinkActor]].
   */
  def props = Props(new DSLinkActor)

  /* CLUSTER */

  /**
   * Starts a shard region for Downstream dslink actors.
   */
  def regionStart(implicit system: ActorSystem): ActorRef = ClusterSharding(system).start(
    Nodes.Downstream,
    props,
    ClusterShardingSettings(system),
    extractEntityId,
    extractShardId)

  /**
   * Starts a shard region proxy (which does not store actors) for Downstream dslink actors.
   */
  def proxyStart(implicit system: ActorSystem): ActorRef = ClusterSharding(system).startProxy(
    Nodes.Downstream,
    Some(BackendRole),
    extractEntityId,
    extractShardId)

  /**
   * Returns the current shard region for Downstream dslink actors.
   */
  def region(implicit system: ActorSystem): ActorRef = ClusterSharding(system).shardRegion(Nodes.Downstream)

  private val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(linkName, payload) => (linkName, payload)
  }

  private val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(linkName, _) => (math.abs(linkName.hashCode) % DownstreamShardCount).toString
  }
}