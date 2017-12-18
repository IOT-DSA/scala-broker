package models.akka.local

import scala.util.control.NonFatal

import akka.actor.{ Actor, ActorLogging, Props, Status, actorRef2Scala }
import models.Settings
import models.akka.{ DSLinkFactory, DSLinkManager }

/**
 * Actor for DSA `/downstream` node.
 * To ensure the correct routing, it needs to be created by the actor system under `downstream`
 * name, so its full path is `/user/downstream`:
 * <pre>
 * actorSystem.actorOf(DownstreamActor.props(...), "downstream")
 * </pre>
 */
class DownstreamActor(dslinkMgr: DSLinkManager) extends Actor with ActorLogging {
  import DownstreamActor._

  if (self.path != self.path.root / "user" / Settings.Nodes.Downstream) {
    val msg = s"Downstream actor should be created under name ${Settings.Nodes.Downstream}"
    log.error(new IllegalStateException(msg), msg + ", not " + self.path)
    context.system.terminate
  }

  private val ownId = "[" + Settings.Paths.Downstream + "]"

  override def preStart = log.debug(s"$ownId actor created")

  override def postStop = log.debug(s"$ownId actor stopped")

  def receive = {
    case GetDSLink(name) => sender ! context.child(name)

    case CreateDSLink(name) => try {
      val child = createDSLink(name)
      sender ! child
    } catch {
      case NonFatal(e) => sender ! Status.Failure(e)
    }

    case GetOrCreateDSLink(name) => try {
      val child = context.child(name) getOrElse createDSLink(name)
      sender ! child
    } catch {
      case NonFatal(e) => sender ! Status.Failure(e)
    }
  }

  /**
   * Creates a new DSLink actor.
   */
  private def createDSLink(name: String) = context.actorOf(DSLinkFactory.props(dslinkMgr), name)
}

/**
 * Factory for [[DownstreamActor]] instances.
 */
object DownstreamActor {

  case class GetDSLink(name: String)
  case class CreateDSLink(name: String)
  case class GetOrCreateDSLink(name: String)

  /**
   * Creates a new instance of [[DownstreamActor]] props.
   */
  def props(dslinkMgr: DSLinkManager) = Props(new DownstreamActor(dslinkMgr))
}