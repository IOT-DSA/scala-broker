package models.akka.local

import akka.actor.{PoisonPill, Props, actorRef2Scala}
import akka.routing.{ActorRefRoutee, Routee}
import models.akka.{DSLinkCreated, DSLinkFolderActor, DSLinkRegistered, DSLinkRemoved, DSLinkUnregistered, IsNode, rows}
import models.akka.Messages._
import models.rpc.DSAValue._

/**
 * Actor for local DSA link folder node, such as `/upstream` or `/downstream`.
 */
class LocalDSLinkFolderActor(linkPath: String, linkProps: Props, extraConfigs: (String, DSAVal)*)
  extends DSLinkFolderActor(linkPath) {

  override def persistenceId = linkPath

  /**
   * Handles incoming messages.
   */
  override def receiveCommand = responderBehavior orElse mgmtHandler

  /**
    * Handles persisted events.
    */
  override def receiveRecover = dslinkFolderRecover orElse responderRecover

  /**
   * Handles control messages.
   */
  private val mgmtHandler: Receive = {

    case GetOrCreateDSLink(name) =>
      persist(DSLinkCreated(name)) { event =>
        log.debug("{}: requested DSLink '{}'", ownId, event.name)
        sender ! getOrCreateDSLink(event.name)
      }

    case RegisterDSLink(name, mode, connected) =>
      persist(DSLinkRegistered(name, mode, connected)) { event =>
        links += (event.name -> LinkState(event.mode, event.connected))
        log.info("{}: registered DSLink '{}'", ownId, event.name)
        notifyOnRegister(event.name)
      }

    case GetDSLinkNames => sender ! links.keys

    case RemoveDSLink(name) =>
      persist(DSLinkRemoved(name)) { event =>
        removeDSLinks(event.name)
        log.debug("{}: ordered to remove DSLink '{}'", ownId, event.name)
      }

    case UnregisterDSLink(name) =>
      persist(DSLinkUnregistered(name)) { event =>
        links -= event.name
        log.info("{}: removed DSLink '{}'", ownId, event.name)
        notifyOnRemove(event.name)
      }

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

  private def newdslink(name: String): Unit = {
    sender ! getOrCreateDSLink(name)
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