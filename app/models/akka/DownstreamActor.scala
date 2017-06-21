package models.akka

import java.util.regex.Pattern

import scala.util.control.NonFatal

import akka.actor.{ Actor, ActorLogging, Props, Status, actorRef2Scala }
import models.Settings
import models.rpc.DSAValue.{ BooleanValue, StringValue, array, obj }
import models.RequestEnvelope
import models.rpc._
import models.ResponseEnvelope

/**
 * Actor for DSA `/downstream` node.
 * To ensure the correct routing, it needs to be created by the actor system under `downstream`
 * name, so its full path is `/user/downstream`:
 * <pre>
 * actorSystem.actorOf(DownstreamActor.props(...), "downstream")
 * </pre>
 */
class DownstreamActor(settings: Settings) extends Actor with ActorLogging {
  import DownstreamActor._
  import StreamState._

  assert(self.path.name == settings.Nodes.Downstream,
    s"Downstream actor should be created under name ${settings.Nodes.Downstream}")

  private val ownId = "[" + settings.Paths.Downstream + "]"

  override def preStart = log.debug(s"$ownId actor created")

  override def postStop = log.debug(s"$ownId actor stopped")

  def receive = {
    case GetDSLink(name) => sender ! context.child(name)

    case CreateDSLink(connInfo) => try {
      val child = createDSLink(connInfo)
      sender ! child
    } catch {
      case NonFatal(e) => sender ! Status.Failure(e)
    }

    case GetOrCreateDSLink(connInfo) => try {
      val child = context.child(connInfo.linkName) getOrElse createDSLink(connInfo)
      sender ! child
    } catch {
      case NonFatal(e) => sender ! Status.Failure(e)
    }

    case GetDSLinkCount => sender ! context.children.size

    case FindDSLinks(regex, limit, offset) =>
      val pattern = Pattern.compile(regex)
      val filtered = context.children.filter(ref => pattern.matcher(ref.path.name).matches)
      val result = filtered.drop(offset).take(limit).map(_.path.name)
      sender ! result

    case RequestEnvelope(from, _, requests) =>
      val envelopes = requests flatMap (processRequest(from, _))
      envelopes foreach (sender ! _)
  }

  /**
   * Creates a new DSLink actor: requester, responder or dual.
   */
  private def createDSLink(ci: ConnectionInfo) = {
    val child = (ci.isRequester, ci.isResponder) match {
      case (true, false) => context.actorOf(RequesterActor.props(settings), ci.linkName)
      case (false, true) => context.actorOf(ResponderActor.props(settings), ci.linkName)
      case (true, true)  => context.actorOf(DualActor.props(settings), ci.linkName)
      case _             => throw new IllegalArgumentException("DSLink must be Requester, Responder or Dual")
    }
    log.debug(s"DSLink[${child.path.name}] created for ${ci.linkName}")
    child
  }

  /**
   * Processes an incoming request and produces a list of response envelopes, if any.
   */
  private def processRequest(from: String, request: DSARequest): TraversableOnce[ResponseEnvelope] = request match {
    case ListRequest(rid, _) =>
      listNodes grouped settings.ChildrenPerListResponse map { rows =>
        val response = DSAResponse(rid = rid, stream = Some(Open), updates = Some(rows.toList))
        ResponseEnvelope(ownId, from, List(response))
      }
    case CloseRequest(rid) =>
      List(ResponseEnvelope(ownId, from, List(DSAResponse(rid = rid, stream = Some(Closed)))))
    case _ =>
      log.error(s"$ownId: invalid request - $request")
      Nil
  }

  /**
   * Generates response for LIST request.
   */
  private def listNodes = {
    val configs = rows(IsNode, "downstream" -> true).toIterable

    val children = context.children map { child =>
      array(child.path.name, obj(IsNode))
    }

    configs ++ children
  }
}

/**
 * Factory for [[DownstreamActor]] instances.
 */
object DownstreamActor {

  case class GetDSLink(name: String)
  case class CreateDSLink(connInfo: ConnectionInfo)
  case class GetOrCreateDSLink(connInfo: ConnectionInfo)
  case object GetDSLinkCount
  case class FindDSLinks(regex: String, limit: Int, offset: Int = 0)

  /**
   * Creates a new instance of [[DownstreamActor]] props.
   */
  def props(settings: Settings) = Props(new DownstreamActor(settings))
}