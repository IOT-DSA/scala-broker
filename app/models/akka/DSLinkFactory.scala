package models.akka

import akka.actor.Props
import models.akka.responder.{ PooledResponderBehavior, ResponderBehavior, SimpleResponderBehavior }
import models.metrics.EventDaos

/**
 * Combines [[AbstractDSLinkActor] with Requester behavior and abstract Responder behavior
 * (to be provided by the subclasses).
 */
abstract class BaseDSLinkActor(val dslinkMgr: DSLinkManager, eventDaos: EventDaos)
  extends AbstractDSLinkActor(eventDaos) with RequesterBehavior with ResponderBehavior {

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
class SimpleDSLinkActor(dslinkMgr: DSLinkManager, eventDaos: EventDaos)
  extends BaseDSLinkActor(dslinkMgr, eventDaos)
  with SimpleResponderBehavior

/**
 * DSLink with a Router/Worker responder implementation, which uses two pools of workers
 * for managing LIST and SUBSCRIBE bindings.
 */
class PooledDSLinkActor(dslinkMgr: DSLinkManager, eventDaos: EventDaos)
  extends BaseDSLinkActor(dslinkMgr, eventDaos)
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

  def createSimpleProps(dslinkMgr: DSLinkManager, eventDaos: EventDaos) =
    Props(new SimpleDSLinkActor(dslinkMgr, eventDaos))

  def createPooledProps(dslinkMgr: DSLinkManager, eventDaos: EventDaos) =
    Props(new PooledDSLinkActor(dslinkMgr, eventDaos))

  def createPubSubProps(dslinkMgr: DSLinkManager, eventDaos: EventDaos) = ???

  def createDPubSubProps(dslinkMgr: DSLinkManager, eventDaos: EventDaos) = ???
}