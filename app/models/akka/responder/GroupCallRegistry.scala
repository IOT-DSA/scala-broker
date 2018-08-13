package models.akka.responder

import akka.event.LoggingAdapter
import models.{Origin, OutResponseEnvelope}
import models.rpc._
import models.rpc.DSAValue.DSAVal

/**
 * Encapsulates information about a List or Subscribe call - the requesters assigned to it
 * and the last received response, if any.
 */
class GroupCallRecord {
  private var _origins = Set.empty[Origin]
  private var _lastResponse: Option[DSAResponse] = None

  def lastResponse = _lastResponse
  def setLastResponse(rsp: DSAResponse) = { _lastResponse = Some(rsp); this }

  def origins = _origins
  def addOrigin(origin: Origin) = { _origins += origin; this }
  def removeOrigin(origin: Origin) = { _origins -= origin; this }
}

/**
 * Manages the call bindings for multi-recipient responses.
 */
abstract class GroupCallRegistry(log: LoggingAdapter, ownId: String) {

  private var bindings = Map.empty[Int, GroupCallRecord]

  /**
   * Looks for the call record containing the specified origin.
   */
  def lookupTargetId(origin: Origin): Option[Int] = bindings.find(_._2.origins.contains(origin)).map(_._1)

  /**
   * Removes the entry for the specified target Id.
   */
  def remove(targetId: Int): Unit = bindings -= targetId

  /**
   * Returns the origins for the specified target Id, or an empty set if the key is not found.
   */
  def getOrigins(targetId: Int): Set[Origin] = bindings.get(targetId).map(_.origins).getOrElse(Set.empty)

  /**
   * Returns the last response for the specified target Id, or None if not found.
   */
  def getLastResponse(targetId: Int): Option[DSAResponse] = bindings.get(targetId).flatMap(_.lastResponse)

  /**
   * Sets the last response for the specified target Id.
   */
  def setLastResponse(targetId: Int, response: DSAResponse): Unit = getOrInsert(targetId).setLastResponse(response)

  /**
   * Adds the origin to the list of recipients for the given target Id.
   */
  def addOrigin(targetId: Int, origin: Origin): Unit = {
    val record = getOrInsert(targetId).addOrigin(origin)
    log.debug(s"$ownId: added binding $targetId -> $origin")
    onAddOrigin(targetId, origin, record)
  }

  /**
   * Removes the origin from the collection of recipients it belongs to. Returns `Some(targetId)`
   * if the call record can be removed (i.e. no listeners left), or None otherwise.
   */
  def removeOrigin(origin: Origin): Option[Int] = bindings.find(_._2.origins contains origin) flatMap {
    case (targetId, record) =>
      record.removeOrigin(origin)
      if (record.origins isEmpty) {
        bindings -= targetId
        Some(targetId)
      } else None
  }

  /**
   * Retrieves a record by the key from the binding map. If the key is not found, insert a new
   * blank record into the map.
   */
  protected def getOrInsert(targetId: Int): GroupCallRecord = bindings.getOrElse(targetId, {
    val wcr = new GroupCallRecord
    bindings += targetId -> wcr
    wcr
  })

  /**
   * Delivers the response to the recipients, must be implemented by the subclasses.
   */
  def deliverResponse(rsp: DSAResponse): Unit

  /**
   * Called when an origin is added to a binding, must be implemented by subclasses.
   */
  protected def onAddOrigin(targetId: Int, origin: Origin, record: GroupCallRecord): Unit
}

/**
 * LIST call registry.
 */
class ListCallRegistry(log: LoggingAdapter, ownId: String) extends GroupCallRegistry(log, ownId) {
  import models.rpc.StreamState._

  /**
   * Sends the last call response (if any) to the new origin.
   */
  protected def onAddOrigin(targetId: Int, origin: Origin, record: GroupCallRecord) = {
    record.lastResponse foreach { response =>
      origin.source ! OutResponseEnvelope(List(response.copy(rid = origin.sourceId)))
    }
  }

  /**
   * Iterates over the origins from the call record and sends out the response to every one of them.
   */
  def deliverResponse(rsp: DSAResponse) = {
    getOrInsert(rsp.rid).setLastResponse(rsp).origins foreach { origin =>
      val response = rsp.copy(rid = origin.sourceId)
      origin.source ! OutResponseEnvelope(List(response))
    }
    if (rsp.stream == Some(StreamState.Closed)) // shouldn't normally happen w/o CLOSE
      remove(rsp.rid)
  }
}

/**
 * SUBSCRIBE  call registry.
 */
class SubscribeCallRegistry(log: LoggingAdapter, ownId: String) extends GroupCallRegistry(log, ownId) {
  import models.rpc.StreamState._

  /**
   * Sends the last call response (if any) to the new origin.
   */
  protected def onAddOrigin(targetId: Int, origin: Origin, record: GroupCallRecord) = {
    record.lastResponse foreach { rsp =>
      val sourceRow = replaceSid(rsp.updates.get.head, origin.sourceId)
      val response = DSAResponse(0, rsp.stream, Some(List(sourceRow)), rsp.columns, rsp.error)
      origin.source ! OutResponseEnvelope(List(response))
    }
  }

  /**
   * Iterates over the origins from the call record and sends out the response to every one of them.
   */
  def deliverResponse(rsp: DSAResponse) = {
    val list = rsp.updates.getOrElse(Nil)
    if (list.isEmpty) {
      log.warning(s"$ownId: cannot find updates in Subscribe response $rsp")
    } else {
      val results = list flatMap handleSubscribeResponseRow(rsp.stream, rsp.columns, rsp.error)
      results groupBy (_._1) mapValues (_.map(_._2)) foreach {
        case (to, rsps) => to ! OutResponseEnvelope(rsps)
      }
    }
  }

  private def handleSubscribeResponseRow(stream: Option[StreamState],
                                         columns: Option[List[ColumnInfo]],
                                         error: Option[DSAError])(row: DSAVal) = {
    val targetSid = extractSid(row)

    val rec = getOrInsert(targetSid)
    rec.setLastResponse(DSAResponse(0, stream, Some(List(row)), columns, error))

    if (stream == Some(StreamState.Closed)) // shouldn't normally happen w/o UNSUBSCRIBE
      remove(targetSid)

    rec.origins map { origin =>
      val sourceRow = replaceSid(row, origin.sourceId)
      val response = DSAResponse(0, stream, Some(List(sourceRow)), columns, error)
      (origin.source, response)
    }
  }
}