package models.akka

import akka.actor.{ActorPath, ActorRef, ActorSystem}
import akka.routing.{ActorSelectionRoutee, Routee}
import models.Settings
import models.Settings.{Paths, Responder}
import play.api.Logger

import scala.concurrent.Future

/**
 * Manages DSA communications and dslink operations.
 */
trait DSLinkManager {

  def updateRoutee(routee: Routee): Routee

  protected val log = Logger(getClass)

  /**
   * Actor system.
   */
  def system: ActorSystem

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
  def getDownlinkRoutee(dsaName: String): Routee

  /**
   * Returns a [[Routee]] that can be used for sending messages to a specific uplink.
   */
  def getUplinkRoutee(dsaName: String): Routee
  
  /**
   * Sends a message to its DSA destination using actor selection.
   */
  def dsaSend(dsaPath: String, message: Any)(implicit sender: ActorRef = ActorRef.noSender): Unit

  /**
    * Ask message from its DSA destination using actor selection
    *
    * @param dsaPath
    * @param message
    * @param sender
    * @return
    */
  def dsaAsk(dsaPath: String, message: Any)(implicit sender: ActorRef = ActorRef.noSender) : Future[Any]

  /**
    * navigates through path
    * @param path
    * @return
    */
  def routee(path:ActorPath, default: ActorPath => Routee):Routee = {
    val nodeType = path.elements.find(name => name == Settings.Nodes.Downstream || name == Settings.Nodes.Upstream)

    nodeType match {
      case Some(Settings.Nodes.Downstream) => getDownlinkRoutee(path.name)
      case Some(Settings.Nodes.Upstream) => getUplinkRoutee(path.name)
      case None => default(path)
    }
  }

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
    case "simple"  => DSLinkFactory.createSimpleProps(this, Paths.Downstream, downstream)
    case "pooled"  => DSLinkFactory.createPooledProps(this, Paths.Downstream, downstream)
    case "pubsub"  => DSLinkFactory.createPubSubProps(this, Paths.Downstream, downstream)
    case "dpubsub" => DSLinkFactory.createDPubSubProps(this, Paths.Downstream, downstream)
  }

  /**
   * An instance of uplink actor props.
   */
  val uplinkProps = DSLinkFactory.createSimpleProps(this, Paths.Upstream, upstream)
}