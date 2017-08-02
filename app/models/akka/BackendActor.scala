package models.akka

import java.util.regex.Pattern

import akka.actor.{ Actor, ActorLogging, PoisonPill, Props, RootActorPath, actorRef2Scala }
import akka.cluster.{ Cluster, Member }
import akka.cluster.ClusterEvent.{ CurrentClusterState, MemberUp }
import akka.cluster.MemberStatus.Up
import models._
import models.rpc._

/**
 * This actor is the broker's backbone. It registers itself with frontend(s) and performs various
 * broker operations. It can be deployed both in local and in cluster environments.
 */
class BackendActor(linkMgr: DSLinkManager) extends Actor with ActorLogging {
  import BackendActor._
  import Messages._
  import StreamState._
  import DSAValue._

  if (self.path != self.path.root / "user" / "backend") {
    val msg = "BackendActor must be deployed under /user/backend"
    log.error(new IllegalStateException(msg), msg + ", not " + self.path)
    context.system.terminate
  }
  private val cluster = context.system.hasExtension(Cluster).option(Cluster(context.system))

  private var links = Map.empty[String, LinkState]

  /**
   * If run in cluster, subscribes to the cluster member events. If not in cluster,
   * register with the local frontend.
   */
  override def preStart() = {
    cluster map (_.subscribe(self, classOf[MemberUp])) getOrElse {
      context.actorSelection("../frontend") ! RegisterBackend
    }
    log.info("BackendActor started at {}", self.path.address)
  }

  /**
   * If run in cluster, unsubscribes from the cluster member events.
   */
  override def postStop() = {
    cluster foreach (_.unsubscribe(self))
    log.info("BackendActor stopped at {}", self.path.address)
  }

  /**
   * Handles incoming messages.
   */
  def receive = receivePublic orElse receivePrivate

  /**
   * Handles public API messages.
   */
  def receivePublic: Receive = {
    case GetDSLinkStats =>
      val requestersOn = links.count(_._2 == LinkState(DSLinkMode.Requester, true))
      val requestersOff = links.count(_._2 == LinkState(DSLinkMode.Requester, false))
      val respondersOn = links.count(_._2 == LinkState(DSLinkMode.Responder, true))
      val respondersOff = links.count(_._2 == LinkState(DSLinkMode.Responder, false))
      val dualsOn = links.count(_._2 == LinkState(DSLinkMode.Dual, true))
      val dualsOff = links.count(_._2 == LinkState(DSLinkMode.Dual, false))
      sender ! DSLinkNodeStats(self.path.address,
        requestersOn, requestersOff,
        respondersOn, respondersOff,
        dualsOn, dualsOff)

    case FindDSLinks(regex, limit, offset) =>
      val pattern = Pattern.compile(regex)
      val filtered = links.keys.filter(pattern.matcher(_).matches).toList.sorted
      val result = filtered.drop(offset).take(limit)
      sender ! result

    case RemoveDisconnectedDSLinks =>
      val disconnected = links.filter(_._2.connected).keys
      disconnected foreach { name => linkMgr.tellDSLink(name, PoisonPill) }
      log.info("Removed {} disconnected DSLinks", disconnected.size)

    case RequestEnvelope(requests) =>
      val envelopes = requests flatMap processRequest
      envelopes foreach (sender ! _)
  }

  /**
   * Handles internal messages.
   */
  def receivePrivate: Receive = {
    case state: CurrentClusterState =>
      log.info("Received current cluster state, registering with frontends")
      state.members.filter(_.status == Up) foreach register

    case MemberUp(m) =>
      log.info("Member Up: {}, registering", m)
      register(m)

    case RegisterDSLink(name, mode, connected) =>
      links += (name -> LinkState(mode, connected))
      log.info("Registered DSLink '{}'", name)

    case evt @ DSLinkStateChanged(name, mode, connected) =>
      log.info("DSLink state changed: {}", evt)
      links.get(name) match {
        case Some(state) => links += (name -> LinkState(mode, connected))
        case None        => log.warning("DSLink {} is not registered, ignoring state change", name)
      }

    case UnregisterDSLink(name) =>
      links -= name
      log.info("Removed DSLink '{}'", name)
  }

  /**
   * Registers this backend actor with a frontend cluster member.
   */
  private def register(member: Member) = if (member.hasRole("frontend")) {
    context.actorSelection(RootActorPath(member.address) / "user" / "frontend") ! RegisterBackend
    log.info("BackendActor registered with frontend at {}", member.address)
  }

  /**
   * Processes an incoming request and produces a list of response envelopes, if any.
   */
  private def processRequest(request: DSARequest): TraversableOnce[ResponseEnvelope] = request match {
    case ListRequest(rid, "/downstream") =>
      listNodes grouped Settings.ChildrenPerListResponse map { rows =>
        val response = DSAResponse(rid = rid, stream = Some(Open), updates = Some(rows.toList))
        ResponseEnvelope(List(response))
      }
    case CloseRequest(rid) =>
      List(ResponseEnvelope(List(DSAResponse(rid = rid, stream = Some(Closed)))))
    case _ =>
      log.error(s"Invalid request - $request")
      Nil
  }

  /**
   * Generates response for LIST request.
   */
  private def listNodes = {
    val configs = rows(IsNode, "downstream" -> true).toIterable

    val children = links.keys map { name =>
      array(name, obj(IsNode))
    }

    configs ++ children
  }
}

/**
 * Common definitions for [[BackendActor]] instances.
 */
object BackendActor {
  import DSLinkMode._

  /**
   * Sent by backend actor(s) to register with the frontend.
   */
  case object RegisterBackend

  /**
   * Sent by a dslink to the backend to registers itself.
   */
  case class RegisterDSLink(name: String, mode: DSLinkMode, connected: Boolean)

  /**
   * Encapsulates DSLink state as seen by the backend.
   */
  private case class LinkState(mode: DSLinkMode, connected: Boolean)

  /**
   * Sent by a dslink to the backend when its status changes.
   */
  case class DSLinkStateChanged(name: String, mode: DSLinkMode, connected: Boolean)

  /**
   * Sent by a dslink to the backend to unregister itself.
   */
  case class UnregisterDSLink(name: String)

  /**
   * Creates a new instance of [[BackendActor]] props.
   */
  def props(linkMgr: DSLinkManager) = Props(new BackendActor(linkMgr))
}