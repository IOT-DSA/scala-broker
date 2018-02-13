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

  protected val log = Logger(getClass)

  /**
   * Actor system.
   */
  def system: ActorSystem

  /**
   * Event DAOs.
   */
  def eventDaos: EventDaos

  /**
   * Downstream node routee.
   */
  val downstream = ActorSelectionRoutee(system.actorSelection("/user" + Paths.Downstream))

  /**
   * Upstream node routee.
   */
  val upstream = ActorSelectionRoutee(system.actorSelection("/user" + Paths.Upstream))

  /**
   * Returns a [[Routee]] that can be used for sending messages to a specific downlink.
   */
  def getDownlinkRoutee(name: String): Routee

  /**
   * Returns a [[Routee]] that can be used for sending messages to a specific uplink.
   */
  def getUplinkRoutee(name: String): Routee
  
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
  val dnlinkProps = Responder.GroupCallEngine match {
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