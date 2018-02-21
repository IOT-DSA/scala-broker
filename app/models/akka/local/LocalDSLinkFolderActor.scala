package models.akka.local

import akka.actor.{ PoisonPill, Props, actorRef2Scala }
import akka.routing.{ ActorRefRoutee, Routee }
import models.akka.{ DSLinkManager, DSLinkFolderActor, IsNode, rows }
import models.akka.Messages._
import models.rpc.DSAValue._

/**
 * Actor for local DSA link folder node, such as `/upstream` or `/downstream`.
 */
class LocalDSLinkFolderActor(linkPath: String, linkProps: Props, extraConfigs: (String, DSAVal)*)
  extends DSLinkFolderActor(linkPath) {

  /**
   * Handles incoming messages.
   */
  def receive = responderBehavior orElse mgmtHandler

  /**
   * Handles control messages.
   */
  val mgmtHandler: Receive = {

    case GetOrCreateDSLink(name) =>
      log.debug("{}: requested DSLink '{}'", ownId, name)
      sender ! getOrCreateDSLink(name)

    case RegisterDSLink(name, mode, connected) =>
      links += (name -> LinkState(mode, connected))
      log.info("{}: registered DSLink '{}'", ownId, name)
      notifyOnRegister(name)

    case GetDSLinkNames => sender ! links.keys

    case RemoveDSLink(name) =>
      removeDSLinks(name)
      log.debug("{}: ordered to remove DSLink '{}'", ownId, name)

    case UnregisterDSLink(name) =>
      links -= name
      log.info("{}: removed DSLink '{}'", ownId, name)
      notifyOnRemove(name)

    case DSLinkStateChanged(name, mode, connected) => changeLinkState(name, mode, connected, true)

    case GetDSLinkStats =>
      val stats = buildDSLinkNodeStats
      sender ! DSLinkStats(Map(stats.address -> stats))

    case FindDSLinks(regex, limit, offset) =>
      sender ! Map(self.path.address -> findDSLinks(regex, limit, offset))

    case RemoveDisconnectedDSLinks => removeDisconnectedDSLinks
  }

  /**
   * Creates/accesses a new DSLink actor and returns a [[Routee]] instance for it.
   */
  protected def getOrCreateDSLink(name: String): Routee = {
    val child = context.child(name) getOrElse {
      log.info("{}: creating a new dslink '{}'", ownId, name)
      context.actorOf(linkProps, name)
    }
    ActorRefRoutee(child)
  }

  /**
   * Terminates the specified DSLink actors.
   */
  protected def removeDSLinks(names: String*) = {
    names map context.child collect { case Some(ref) => ref } foreach (_ ! PoisonPill)
  }

  /**
   * Generates a list of values in response to LIST request.
   */
  protected def listNodes: Iterable[ArrayValue] = {
    val configs = rows(IsNode) ++ rows(extraConfigs: _*)

    val children = links.keys map (name => array(name, obj(IsNode)))

    configs ++ children
  }
}

/**
 * Factory for [[LocalDSLinkFolderActor]] instances.
 */
object LocalDSLinkFolderActor {

  /**
   * Creates a new props for [[LocalDSLinkFolderActor]].
   */
  def props(linkPath: String, linkProps: Props, extraConfigs: (String, DSAVal)*) =
    Props(new LocalDSLinkFolderActor(linkPath, linkProps, extraConfigs: _*))
}