package models.akka.local

import akka.actor.{ PoisonPill, Props, actorRef2Scala }
import akka.routing.{ ActorRefRoutee, Routee }
import models.akka.{ DSLinkFactory, DSLinkManager, DownstreamActor, IsNode, rows }
import models.metrics.EventDaos

/**
 * Actor for DSA `/downstream` node.
 * To ensure the correct routing, it needs to be created by the actor system under `downstream`
 * name, so its full path is `/user/downstream`:
 * <pre>
 * actorSystem.actorOf(LocalDownstreamActor.props(...), "downstream")
 * </pre>
 */
class LocalDownstreamActor(dslinkMgr: DSLinkManager, eventDaos: EventDaos) extends DownstreamActor {
  import models.akka.Messages._
  import models.rpc.DSAValue._

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

    case FindDSLinks(regex, limit, offset) => sender ! findDSLinks(regex, limit, offset)

    case RemoveDisconnectedDSLinks         => removeDisconnectedDSLinks
  }

  /**
   * Creates/accesses a new DSLink actor and returns a [[Routee]] instance for it.
   */
  protected def getOrCreateDSLink(name: String): Routee = {
    val child = context.child(name) getOrElse {
      log.info("{}: creating a new dslink '{}'", ownId, name)
      context.actorOf(DSLinkFactory.props(dslinkMgr, eventDaos), name)
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
    val configs = rows(IsNode, "downstream" -> true).toIterable

    val children = links.keys map (name => array(name, obj(IsNode)))

    configs ++ children
  }
}

/**
 * Factory for [[LocalDownstreamActor]] instances.
 */
object LocalDownstreamActor {

  /**
   * Creates a new props for [[LocalDownstreamActor]].
   */
  def props(dslinkMgr: DSLinkManager, eventDaos: EventDaos) = Props(new LocalDownstreamActor(dslinkMgr, eventDaos))
}