package models.akka

import akka.actor.Props
import models.akka.responder.{PooledResponderBehavior, ResponderBehavior, SimpleResponderBehavior}
import akka.routing.Routee

/**
 * Combines [[AbstractDSLinkActor] with Requester behavior and abstract Responder behavior
 * (to be provided by the subclasses).
 */
abstract class BaseDSLinkActor(dsaParent: String, registry: Routee) extends AbstractDSLinkActor(registry)
  with RequesterBehavior with ResponderBehavior {
  
  protected val linkPath = dsaParent + "/" + linkName

  /**
   * Performs post-stop actions on the requester.
   */
  override def postStop() = {
    stopRequester
    super.postStop
  }

  /**
   * Handles messages in CONNECTED state.
   */
  override def connected = super.connected orElse requesterBehavior orElse responderBehavior
}

/**
 * DSLink with a simple responder implementation, which uses local registries for LIST and
 * SUBSCRIBE calls.
 */
class SimpleDSLinkActor(val dslinkMgr: DSLinkManager, dsaParent: String, registry: Routee)
  extends BaseDSLinkActor(dsaParent, registry)
  with SimpleResponderBehavior

/**
 * DSLink with a Router/Worker responder implementation, which uses two pools of workers
 * for managing LIST and SUBSCRIBE bindings.
 */
class PooledDSLinkActor(val dslinkMgr: DSLinkManager, dsaParent: String, registry: Routee)
  extends BaseDSLinkActor(dsaParent, registry)
  with PooledResponderBehavior

/**
 * Factory for DSLink actors, supports the following responder implementation:
 * <ul>
 * 	<li>`simple`</li> - Basic DSLink implementation, uses local registry.
 * 	<li>`pooled`</li> - Router/Worker implementation, uses worker actor pools.
 *  <li>`pubsub`</li> - EventBus implementation, uses local subscriptions.
 *  <li>`dpubsub`</li> - Distributed PubSub implementation, uses cluster-wide subscriptions.
 * </ul>
 */
object DSLinkFactory {

  def createSimpleProps(dslinkMgr: DSLinkManager, dsaParent: String, registry: Routee) =
    Props(new SimpleDSLinkActor(dslinkMgr, dsaParent, registry))

  def createPooledProps(dslinkMgr: DSLinkManager, dsaParent: String, registry: Routee) =
    Props(new PooledDSLinkActor(dslinkMgr, dsaParent, registry))

  def createPubSubProps(dslinkMgr: DSLinkManager, dsaParent: String, registry: Routee) = ???

  def createDPubSubProps(dslinkMgr: DSLinkManager, dsaParent: String, registry: Routee) = ???
}