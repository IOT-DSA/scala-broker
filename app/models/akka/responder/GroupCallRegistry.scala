package models.akka.responder

import akka.event.LoggingAdapter
import persistence._
import models.{Origin, ResponseEnvelope}
import models.rpc._
import models.rpc.DSAValue.DSAVal

/**
 * Encapsulates information about a List or Subscribe call - the requesters assigned to it
 * and the last received response, if any.
 */
class GroupCallRecord {
  private var _origins = Set.empty[Origin]
  private var _lastResponse: Option[DSAResponse] = None

  def lastResponse: Option[DSAResponse] = _lastResponse
  def setLastResponse(rsp: DSAResponse): GroupCallRecord = { _lastResponse = Some(rsp); this }

  def origins: Set[Origin] = _origins
  def addOrigin(origin: Origin): GroupCallRecord = { _origins += origin; this }
  def removeOrigin(origin: Origin): GroupCallRecord = { _origins -= origin; this }
}

/**
 * Manages the call bindings for multi-recipient responses.
 */
abstract class GroupCallRegistry() {

  private var _bindings = Map.empty[Int, GroupCallRecord]

  /**
    * Internal API for [[_bindings]] changing
    */
  private def internalRemove(targetId: Int): Unit = _bindings -= targetId
  private def internalAddOrigin(targetId: Int, origin: Origin) = getOrInsert(targetId).addOrigin(origin)
  private def internalRemoveOrigin(origin: Origin) = _bindings.find(_._2.origins contains origin) flatMap {
    case (targetId, record) =>
      record.removeOrigin(origin)
      if (record.origins isEmpty) internalRemove(targetId)
      None
  }

  /**
    * Returns all bindings especially for quick snapshotting.
    */
  def getBindings: Map[Int, GroupCallRecord] = _bindings

  /**
    * Sets bindings to quick restore the state from snapshot.
    */
  def setBindings(bindings: Map[Int, GroupCallRecord]): Unit = _bindings = bindings

  /**
   * Looks for the call record containing the specified origin.
   */
  def lookupTargetId(origin: Origin): Option[Int] = _bindings.find(_._2.origins.contains(origin)).map(_._1)

  /**
   * Removes the entry for the specified target Id and persist the current event.
   */
  def remove(targetId: Int, regTypeVal: RegistryType.Registry): Unit =  internalRemove(targetId)


  /**
   * Returns the origins for the specified target Id, or an empty set if the key is not found.
   */
  def getOrigins(targetId: Int): Set[Origin] = _bindings.get(targetId).map(_.origins).getOrElse(Set.empty)

  /**
   * Returns the last response for the specified target Id, or None if not found.
   */
  def getLastResponse(targetId: Int): Option[DSAResponse] = _bindings.get(targetId).flatMap(_.lastResponse)

  /**
   * Sets the last response for the specified target Id.
   */
  def setLastResponse(targetId: Int, response: DSAResponse): Unit = getOrInsert(targetId).setLastResponse(response)

  /**
   * Adds the origin to the list of recipients for the given target Id.
   */
  def addOrigin(targetId: Int, origin: Origin, regTypeVal: RegistryType.Registry): Unit = {
    val record = internalAddOrigin(targetId, origin)
    onAddOrigin(targetId, origin, record)
  }

  /**
   * Removes the origin from the collection of recipients it belongs to. Returns `Some(targetId)`
   * if the call record can be removed (i.e. no listeners left), or None otherwise.
   */
  def removeOrigin(origin: Origin, regTypeVal: RegistryType.Registry): Option[Int] = _bindings.find(_._2.origins contains origin) flatMap {
    case (targetId, record) =>
      // check for the last origin, will be removed
      var theLastOne = false
      if (record.origins.size == 1) theLastOne = true

      record.removeOrigin(origin)
      if (theLastOne) internalRemove(targetId)

      if (theLastOne) Some(targetId) else None
  }

  def restoreGroupCallRegistry(event: GroupCallRegistryRestoreProcess): Any = event match {
    case e: RecordRemoved =>
      internalRemove(e.targetId)
    case e: OriginAdded =>
      internalAddOrigin(e.targetId, e.origin)
    case e: OriginRemoved =>
      internalRemoveOrigin(e.origin)
  }

  /**
   * Retrieves a record by the key from the binding map. If the key is not found, insert a new
   * blank record into the map.
   */
  protected def getOrInsert(targetId: Int): GroupCallRecord = _bindings.getOrElse(targetId, {
    val wcr = new GroupCallRecord
    _bindings += targetId -> wcr
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
class ListCallRegistry(ownId: String, log: LoggingAdapter) extends GroupCallRegistry {
  import models.akka.RichRoutee

  /**
   * Sends the last call response (if any) to the new origin.
   */
  protected def onAddOrigin(targetId: Int, origin: Origin, record: GroupCallRecord): Unit = {
    record.lastResponse foreach { response =>
      origin.source ! ResponseEnvelope(List(response.copy(rid = origin.sourceId)))
    }
  }

  /**
   * Iterates over the origins from the call record and sends out the response to every one of them.
   */
  def deliverResponse(rsp: DSAResponse): Unit = {
    getOrInsert(rsp.rid).setLastResponse(rsp).origins foreach { origin =>
      val response = rsp.copy(rid = origin.sourceId)
      log.debug("{}: deliverResponse sends '{}' to '{}'", ownId, response, origin.source)
      origin.source ! ResponseEnvelope(List(response))
    }
    if (rsp.stream.contains(StreamState.Closed)) // shouldn't normally happen w/o CLOSE
      remove(rsp.rid, RegistryType.LIST)
  }
}

/**
 * SUBSCRIBE  call registry.
 */
class SubscribeCallRegistry(ownId: String, log: LoggingAdapter) extends GroupCallRegistry {
  import models.rpc.StreamState._
  import models.akka.RichRoutee

  /**
   * Sends the last call response (if any) to the new origin.
   */
  protected def onAddOrigin(targetId: Int, origin: Origin, record: GroupCallRecord): Unit = {
    record.lastResponse foreach { rsp =>
      val sourceRow = replaceSid(rsp.updates.get.head, origin.sourceId)
      val response = DSAResponse(0, rsp.stream, Some(List(sourceRow)), rsp.columns, rsp.error)
      origin.source ! ResponseEnvelope(List(response))
    }
  }

  /**
   * Iterates over the origins from the call record and sends out the response to every one of them.
   */
  def deliverResponse(rsp: DSAResponse) = {
    val list = rsp.updates.getOrElse(Nil)
    if (list.isEmpty) {
      log.warning("{}: cannot find updates in Subscribe response {}", ownId, rsp)
    } else {
      val results = list flatMap handleSubscribeResponseRow(rsp.stream, rsp.columns, rsp.error)
      log.debug("{}: deliverResponse results: {}", ownId, results)
      results groupBy (_._1) mapValues (_.map(_._2)) foreach {
        case (to, rsps) =>
          log.debug("{}: deliverResponse sends '{}' to '{}'", ownId, rsps, to)
          to ! ResponseEnvelope(rsps)
      }
    }
  }

  private def handleSubscribeResponseRow(stream: Option[StreamState],
                                         columns: Option[List[ColumnInfo]],
                                         error: Option[DSAError])(row: DSAVal) = {
    val targetSid = extractSid(row)

    val rec = getOrInsert(targetSid)
    rec.setLastResponse(DSAResponse(0, stream, Some(List(row)), columns, error))

    if (stream.contains(StreamState.Closed)) // shouldn't normally happen w/o UNSUBSCRIBE
      remove(targetSid, RegistryType.SUBS)

    rec.origins map { origin =>
      val sourceRow = replaceSid(row, origin.sourceId)
      val response = DSAResponse(0, stream, Some(List(sourceRow)), columns, error)
      (origin.source, response)
    }
  }
}

object RegistryType extends Enumeration {
  type Registry = Value
  val LIST, SUBS, DEFAULT = Value
}
