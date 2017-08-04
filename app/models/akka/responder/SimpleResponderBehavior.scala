package models.akka.responder

import models.Origin
import models.rpc.DSAResponse
import models.akka.AbstractDSLinkActor

/**
 * Handles communication with a remote DSLink in Responder mode using local maps
 * for implementing multi-recipient responce delivery (LIST, SUBSCRIBE).
 */
trait SimpleResponderBehavior extends ResponderBehavior { me: AbstractDSLinkActor =>

  private val listRegistry = new ListCallRegistry(log, ownId + "-LIST")
  private val subsRegistry = new SubscribeCallRegistry(log, ownId + "-SUBS")

  /**
   * Adds the origin to the list of recipients for the given target RID.
   */
  protected def addListOrigin(targetId: Int, origin: Origin) = listRegistry.addOrigin(targetId, origin)

  /**
   * Adds the origin to the list of recipients for the given target SID.
   */
  protected def addSubscribeOrigin(targetId: Int, origin: Origin) = subsRegistry.addOrigin(targetId, origin)

  /**
   * Removes the origin from the collection of LIST recipients it belongs to. Returns `Some(targetId)`
   * if the call record can be removed (i.e. no listeners left), or None otherwise.
   */
  protected def removeListOrigin(origin: Origin) = listRegistry.removeOrigin(origin)

  /**
   * Removes the origin from the collection of SUBSCRIBE recipients it belongs to. Returns `Some(targetId)`
   * if the call record can be removed (i.e. no listeners left), or None otherwise.
   */
  protected def removeSubscribeOrigin(origin: Origin) = subsRegistry.removeOrigin(origin)

  /**
   * Delivers a LIST response to its recipients.
   */
  protected def deliverListResponse(rsp: DSAResponse) = listRegistry.deliverResponse(rsp)

  /**
   * Delivers a SUBSCRIBE response to its recipients.
   */
  protected def deliverSubscribeResponse(rsp: DSAResponse) = subsRegistry.deliverResponse(rsp)
}