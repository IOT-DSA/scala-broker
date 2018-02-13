package models.akka

import akka.actor.{ ActorRef, ActorSystem }
import akka.routing.{ ActorSelectionRoutee, Routee }
import models.Settings.{ Nodes, Paths, Responder }
import models.metrics.EventDaos
import play.api.Logger

/**
 * Manages DSA communications and dslink operations.
 */
trait DSLinkManager {

  /**
   * Actor system.
   */
  def system: ActorSystem

  /**
   * Event DAOs.
   */
  def eventDaos: EventDaos

  protected val log = Logger(getClass)

  /**
   * Downstream node routee.
   */
  protected def downstream = ActorSelectionRoutee(system.actorSelection("/user" + Paths.Downstream))

  /**
   * Upstream node routee.
   */
  protected def upstream = ActorSelectionRoutee(system.actorSelection("/user/" + Nodes.Root + Paths.Upstream))

  /**
   * Returns a [[Routee]] that can be used for sending messages to a specific dslink.
   */
  def getDSLinkRoutee(name: String): Routee

  /**
   * Sends a message to its DSA destination using actor selection.
   */
  def dsaSend(path: String, message: Any)(implicit sender: ActorRef = ActorRef.noSender): Unit

  /**
   * An instance of downlink actor props, according to the
   * `broker.responder.group.call.engine` config settings:
   * <ul>
   * 	<li>`simple`</li> - Basic DSLink implementation, uses local registry.
   * 	<li>`pooled`</li> - Router/Worker implementation, uses worker actor pools.
   *  <li>`pubsub`</li> - EventBus implementation, uses local subscriptions.
   *  <li>`dpubsub`</li> - Distributed PubSub implementation, uses cluster-wide subscriptions.
   * </ul>
   */
  val downlinkProps = Responder.GroupCallEngine match {
    case "simple"  => DSLinkFactory.createSimpleProps(this, Paths.Downstream, downstream, eventDaos)
    case "pooled"  => DSLinkFactory.createPooledProps(this, Paths.Downstream, downstream, eventDaos)
    case "pubsub"  => DSLinkFactory.createPubSubProps(this, Paths.Downstream, downstream, eventDaos)
    case "dpubsub" => DSLinkFactory.createDPubSubProps(this, Paths.Downstream, downstream, eventDaos)
  }

  /**
   * An instance of uplink actor props.
   */
  val uplinkProps = DSLinkFactory.createSimpleProps(this, Paths.Upstream, upstream, eventDaos)
}