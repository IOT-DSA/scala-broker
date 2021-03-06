package models.akka.responder

import akka.actor._
import akka.persistence.PersistentActor
import models.Origin
import models.akka.{GroupCallRegistryRestoreProcess, MainResponderBehaviorState, ResponderBehaviorState, RouteeNavigator, SimpleResponderBehaviorState}
import models.rpc.DSAResponse
import OriginUpdater._

/**
  * Handles communication with a remote DSLink in Responder mode using local maps
  * for implementing multi-recipient responce delivery (LIST, SUBSCRIBE).
  */
trait SimpleResponderBehavior extends ResponderBehavior { me: RouteeNavigator with PersistentActor with ActorLogging =>

  private val listRegistry = new ListCallRegistry(new PartOfPersistentResponderBehavior(ownId + "-LIST", log))
  private val subsRegistry = new SubscribeCallRegistry(new PartOfPersistentResponderBehavior(ownId + "-SUBS", log))

  val simpleResponderRecover: Receive = {
    case event: GroupCallRegistryRestoreProcess =>
      val updated = event.update(updateRoutee)
      log.debug("{}: recovering with event {}", ownId, updated)
      if (updated.value == RegistryType.LIST) listRegistry.restoreGroupCallRegistry(updated)
      if (updated.value == RegistryType.SUBS) subsRegistry.restoreGroupCallRegistry(updated)
    case offeredSnapshot: SimpleResponderBehaviorState =>
      val updated = offeredSnapshot.update(updateRoutee)
      log.info("{}: recovering responder state with snapshot {}", ownId, updated)
      listRegistry.setBindings(updated.listBindings)
      subsRegistry.setBindings(updated.subsBindings)
  }

  /**
    * Adds the origin to the list of recipients for the given target RID.
    */
  protected def addListOrigin(targetId: Int, origin: Origin) = listRegistry.addOrigin(targetId, origin, RegistryType.LIST)

  /**
    * Adds the origin to the list of recipients for the given target SID.
    */
  protected def addSubscribeOrigin(targetId: Int, origin: Origin) = subsRegistry.addOrigin(targetId, origin, RegistryType.SUBS)

  /**
    * Removes the origin from the collection of LIST recipients it belongs to. Returns `Some(targetId)`
    * if the call record can be removed (i.e. no listeners left), or None otherwise.
    */
  protected def removeListOrigin(origin: Origin) = listRegistry.removeOrigin(origin, RegistryType.LIST)

  /**
    * Removes the origin from the collection of SUBSCRIBE recipients it belongs to. Returns `Some(targetId)`
    * if the call record can be removed (i.e. no listeners left), or None otherwise.
    */
  protected def removeSubscribeOrigin(origin: Origin) = subsRegistry.removeOrigin(origin, RegistryType.SUBS)

  /**
    * Delivers a LIST response to its recipients.
    */
  protected def deliverListResponse(rsp: DSAResponse) = listRegistry.deliverResponse(rsp)

  /**
    * Delivers a SUBSCRIBE response to its recipients.
    */
  protected def deliverSubscribeResponse(rsp: DSAResponse) = subsRegistry.deliverResponse(rsp)

  /**
    * Tries to save this responder state as a snapshot.
    */
  protected def saveResponderBehaviorSnapshot(main: MainResponderBehaviorState) =
    saveSnapshot(ResponderBehaviorState(main, SimpleResponderBehaviorState(listRegistry.getBindings, subsRegistry.getBindings)))
}
