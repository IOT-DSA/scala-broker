package models.akka.cluster

import java.util.regex.Pattern

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.Duration

import akka.actor.{ Actor, ActorLogging, Address, Props, RootActorPath }
import akka.pattern.ask
import akka.cluster.{ Member, UniqueAddress, Cluster }
import akka.cluster.ClusterEvent.{ CurrentClusterState, MemberUp }
import akka.util.Timeout
import models.{ RequestEnvelope, ResponseEnvelope, Settings }
import models.rpc.{ CloseRequest, DSARequest, DSAResponse, ListRequest }
import models.rpc.DSAValue.{ BooleanValue, StringValue, array, obj }

/**
 * Manages the broker backend operations.
 */
class BackendActor extends Actor with ActorLogging {
  import BackendActor._
  import akka.cluster.MemberStatus._
  import models.rpc.StreamState._

  implicit val timeout = Timeout(Settings.QueryTimeout)
  import context.dispatcher

  private val cluster = Cluster(context.system)

  private var linkNames = Seq.empty[String]

  /**
   * Subscribes to the cluster member events.
   */
  override def preStart() = {
    cluster.subscribe(self, classOf[MemberUp])
    log.info("BackendActor started at {}", self.path.toStringWithAddress(cluster.selfAddress))
  }

  /**
   * Unsubscribes from the cluster member events.
   */
  override def postStop() = {
    cluster.unsubscribe(self)
    log.info("BackendActor stopped at {}", self.path.toStringWithAddress(cluster.selfAddress))
  }

  /**
   * Handles incoming messages.
   */
  def receive = {
    case state: CurrentClusterState => state.members.filter(_.status == Up) foreach register

    case MemberUp(m)                => register(m)

    case GetMemberInfo =>
      sender ! MemberInfo(cluster.selfAddress, cluster.selfUniqueAddress, cluster.selfRoles)

    case RegisterDSLink =>
      linkNames = linkNames :+ sender.path.name
      log.info("Registered DSLink '{}'", sender.path.name)

    case UnregisterDSLink =>
      linkNames = linkNames.filterNot(_ == sender.path.name)
      log.info("Removed DSLink '{}'", sender.path.name)

    case GetDSLinkCount => sender ! linkNames.size

    case FindDSLinks(regex, limit, offset) =>
      val pattern = Pattern.compile(regex)
      val filtered = linkNames.filter(pattern.matcher(_).matches).sorted
      val result = filtered.drop(offset).take(limit)
      sender ! result

    case GetAllDSLinks => sender ! linkNames

    case RequestEnvelope(requests) =>
      val envelopes = requests flatMap processRequest
      envelopes foreach (sender ! _)
  }

  /**
   * Registers this backend actor with a frontend cluster member.
   */
  private def register(member: Member) = if (member.hasRole("frontend")) {
    context.actorSelection(RootActorPath(member.address) / "user" / "frontend") ! BackendRegistration
    log.info("BackendActor registered with frontend at {}", member.address)
  }

  /**
   * Processes an incoming request and produces a list of response envelopes, if any.
   */
  private def processRequest(request: DSARequest): TraversableOnce[ResponseEnvelope] = request match {
    case ListRequest(rid, _) =>
      listDownstreamNodes grouped Settings.ChildrenPerListResponse map { rows =>
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
   * Generates response for Downstream LIST request.
   */
  private def listDownstreamNodes = {
    val configs = rows(IsNode, "downstream" -> true).toIterable

    val backends = cluster.state.members filter { m =>
      cluster.selfUniqueAddress != m.uniqueAddress && m.roles.contains("backend") && m.status == Up
    } map { m =>
      context.actorSelection(RootActorPath(m.address) / "user" / "backend")
    }

    val fLinks = Future.sequence(backends.toList map (b => (b ? GetAllDSLinks).mapTo[Seq[String]])) map {
      _.flatMap(identity)
    }

    //TODO needs to be left as future and piped
    val otherLinks = Await.result(fLinks, Duration.Inf)

    val children = (otherLinks ++ linkNames) map { name =>
      array(name, obj(IsNode))
    }

    configs ++ children
  }
}

/**
 * Messages for [[BackendActor]].
 */
object BackendActor {

  /**
   * Sent to other cluster members to register this backend.
   */
  case object BackendRegistration

  /**
   * Requests information for the backend's cluster member.
   */
  case object GetMemberInfo

  /**
   * Registers a dslink with this backend actor.
   */
  case object RegisterDSLink

  /**
   * Unregisters a dslink from this backend actor.
   */
  case object UnregisterDSLink

  /**
   * Requests the number of DSLinks registered with this backend.
   */
  case object GetDSLinkCount

  /**
   * Searches through the DSLinks registered with this backend.
   */
  case class FindDSLinks(regex: String, limit: Int, offset: Int = 0)

  /**
   * Requests the names of all DSLinks registered with this backend.
   */
  case object GetAllDSLinks

  /**
   * Returned to the caller in response to [[GetMemberInfo]] message.
   */
  case class MemberInfo(address: Address, uniqueAddress: UniqueAddress, roles: Set[String])

  /**
   * Creates a new [[BackendActor]] Props instance.
   */
  def props = Props(new BackendActor)
}