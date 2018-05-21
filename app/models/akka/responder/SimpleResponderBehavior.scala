package models.akka.responder

import akka.actor._
import akka.persistence.PersistentActor

import models.Origin
import models.rpc.DSAResponse
import models.akka.{ OriginAddedToListRegistry, OriginAddedToSubsRegistry, ListOriginRemoved, SubsOriginRemoved, ListResponseDelivered, SubsResponseDelivered }

/**
 * Handles communication with a remote DSLink in Responder mode using local maps
 * for implementing multi-recipient responce delivery (LIST, SUBSCRIBE).
 */
trait SimpleResponderBehavior extends ResponderBehavior { me: PersistentActor with ActorLogging =>

  private val listRegistry = new ListCallRegistry(log, ownId + "-LIST")
  private val subsRegistry = new SubscribeCallRegistry(log, ownId + "-SUBS")

  /**
    * Recovers events of simple responder behavior from the journal.
    */
  val simpleResponderRecover: Receive = {
    case event: OriginAddedToListRegistry =>
      log.debug("{}: trying to recover {}", ownId, event)
      listRegistry.addOrigin(event.targetId, event.origin)
    case event: OriginAddedToSubsRegistry =>
      log.debug("{}: trying to recover {}", ownId, event)
      subsRegistry.addOrigin(event.targetId, event.origin)
    case event: ListOriginRemoved => // TODO Not realized yet. ID return and removing operation have to be separated.
      log.debug("{}: trying to recover {}", ownId, event)
      listRegistry.removeOrigin(event.origin)
    case event: SubsOriginRemoved => // TODO Not realized yet. ID return and removing operation have to be separated.
      log.debug("{}: trying to recover {}", ownId, event)
      subsRegistry.removeOrigin(event.origin)
    case event: ListResponseDelivered =>
      log.debug("{}: trying to recover {}", ownId, event)
      listRegistry.deliverResponse(event.rsp)
    case event: SubsResponseDelivered =>
      log.debug("{}: trying to recover {}", ownId, event)
      subsRegistry.deliverResponse(event.rsp)
  }

  /**
   * Adds the origin to the list of recipients for the given target RID.
   */
  protected def addListOrigin(targetId: Int, origin: Origin) = {
    persist(OriginAddedToListRegistry(targetId, origin)) { event =>
      listRegistry.addOrigin(event.targetId, event.origin)
    }
  }

  /**
   * Adds the origin to the list of recipients for the given target SID.
   */
  protected def addSubscribeOrigin(targetId: Int, origin: Origin) = {
    persist(OriginAddedToSubsRegistry(targetId, origin)) { event =>
      subsRegistry.addOrigin(event.targetId, event.origin)
    }
  }

  /**
   * Removes the origin from the collection of LIST recipients it belongs to. Returns `Some(targetId)`
   * if the call record can be removed (i.e. no listeners left), or None otherwise.
   */
  protected def removeListOrigin(origin: Origin) = {
//    persist(ListOriginRemoved(origin)) { event =>
//      listRegistry.removeOrigin(event.origin)
//    }
    listRegistry.removeOrigin(origin)
  }

  /**
   * Removes the origin from the collection of SUBSCRIBE recipients it belongs to. Returns `Some(targetId)`
   * if the call record can be removed (i.e. no listeners left), or None otherwise.
   */
  protected def removeSubscribeOrigin(origin: Origin) = {
//    persist(SubsOriginRemoved(origin)) { event =>
//      subsRegistry.removeOrigin(event.origin)
//    }
    subsRegistry.removeOrigin(origin)
  }

  /**
   * Delivers a LIST response to its recipients.
   */
  protected def deliverListResponse(rsp: DSAResponse) = {
    persist(ListResponseDelivered(rsp)) { event =>
      listRegistry.deliverResponse(event.rsp)
    }
  }

  /**
   * Delivers a SUBSCRIBE response to its recipients.
   */
  protected def deliverSubscribeResponse(rsp: DSAResponse) = {
    persist(SubsResponseDelivered(rsp)) { event =>
      subsRegistry.deliverResponse(event.rsp)
    }
  }
}