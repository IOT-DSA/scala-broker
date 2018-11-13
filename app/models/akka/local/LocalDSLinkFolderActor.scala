package models.akka.local

import akka.actor.{PoisonPill, Props, actorRef2Scala}
import akka.routing.{ActorRefRoutee, Routee}
import models.akka.{DSLinkCreated, DSLinkFolderActor, DSLinkFolderState, DSLinkRegistered, DSLinkRemoved, DSLinkUnregistered, IsNode, rows}
import akka.stream.scaladsl.Source
import models.akka.Messages._
import models.rpc.DSAValue._
import models.util.DsaToAkkaCoder._

/**
  * Actor for local DSA link folder node, such as `/upstream` or `/downstream`.
  */
class LocalDSLinkFolderActor(linkPath: String, linkProps: Props, extraConfigs: (String, DSAVal)*)
  extends DSLinkFolderActor(linkPath) {

  override def persistenceId = linkPath

  /**
   * Handles incoming messages.
   */
  override def receiveCommand = responderBehavior orElse mgmtHandler orElse snapshotReceiver

  /**
    * Handles persisted events.
    */
  override def receiveRecover = dslinkFolderRecover orElse responderRecover orElse simpleResponderRecover orElse recoverDSLinkSnapshot

  /**
   * Handles control messages.
   */
  private val mgmtHandler: Receive = {

    case GetOrCreateDSLink(name) =>
      persist(DSLinkCreated(name)) { event =>
        log.debug("{}: persisting {}", ownId, event)
        log.debug("{}: requested DSLink '{}'", ownId, event.name)
        sender ! getOrCreateDSLink(event.name)
      }

    case RegisterDSLink(name, mode, connected) =>
      persist(DSLinkRegistered(name, mode, connected)) { event =>
        log.debug("{}: persisting {}", ownId, event)
        links += (event.name -> LinkState(event.mode, event.connected))
        saveSnapshot(DSLinkFolderState(links, listRid))
        log.info("{}: registered DSLink '{}'", ownId, event.name)
        notifyOnRegister(event.name)
      }

    case GetDSLinkNames => sender ! links.keys

    case RemoveDSLink(name) =>
      persist(DSLinkRemoved(name)) { event =>
        log.debug("{}: persisting {}", ownId, event)
        removeDSLinks(event.names: _*)
        log.debug("{}: ordered to remove DSLink '{}'", ownId, event.names)
      }

    case UnregisterDSLink(name) =>
      persist(DSLinkUnregistered(name)) { event =>
        log.debug("{}: persisting {}", ownId, event)
        links -= event.name
        saveSnapshot(DSLinkFolderState(links, listRid))
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
    val child = context.child(name.forAkka) getOrElse {
      log.info("{}: creating a new dslink '{}'", ownId, name)
      context.actorOf(linkProps, name.forAkka)
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
    names map(_.forAkka) map context.child collect { case Some(ref) => ref } foreach (_ ! PoisonPill)
  }

  /**
    * Generates a list of values in response to LIST request.
    */
  protected def listNodes: Source[ArrayValue, _] = {
    val configs = rows(IsNode) ++ rows(extraConfigs: _*)

    val children = links.keys map (name => array(name, obj(IsNode)))

    Source(configs ++ children)
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