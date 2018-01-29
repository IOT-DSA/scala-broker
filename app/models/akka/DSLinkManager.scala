package models.akka

import akka.actor.ActorRef
import akka.routing.Routee
import models.Settings
import models.metrics.EventDaos
import play.api.Logger

/**
 * Manages DSA communications and dslink operations.
 */
trait DSLinkManager {

  /**
   * Event DAOs.
   */
  def eventDaos: EventDaos

  protected val log = Logger(getClass)

  /**
   * Returns a [[Routee]] that can be used for sending messages to a specific dslink.
   */
  def getDSLinkRoutee(name: String): Routee

  /**
   * Sends a message to its DSA destination using actor selection.
   */
  def dsaSend(path: String, message: Any)(implicit sender: ActorRef = ActorRef.noSender): Unit

  /**
   * Creates a new instance of DSLink actor props, according to the
   * `broker.responder.group.call.engine` config settings:
   * <ul>
   * 	<li>`simple`</li> - Basic DSLink implementation, uses local registry.
   * 	<li>`pooled`</li> - Router/Worker implementation, uses worker actor pools.
   *  <li>`pubsub`</li> - EventBus implementation, uses local subscriptions.
   *  <li>`dpubsub`</li> - Distributed PubSub implementation, uses cluster-wide subscriptions.
   * </ul>
   */
  val props = Settings.Responder.GroupCallEngine match {
    case "simple"  => DSLinkFactory.createSimpleProps(this, eventDaos)
    case "pooled"  => DSLinkFactory.createPooledProps(this, eventDaos)
    case "pubsub"  => DSLinkFactory.createPubSubProps(this, eventDaos)
    case "dpubsub" => DSLinkFactory.createDPubSubProps(this, eventDaos)
  }
}