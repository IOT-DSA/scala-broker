package models.akka

import models.Origin
import models.rpc.DSAResponse
import models.rpc.DSAMethod.DSAMethod

/**
 * Encapsulates DSLink information for WebSocket connection.
 */
case class ConnectionInfo(dsId: String, isRequester: Boolean, isResponder: Boolean, linkPath: String) {
  //TODO switch derive linkPath from linkName
  val linkName = dsId.substring(0, dsId.length - 44)
}

/**
 * Similar to [[java.util.concurrent.atomic.AtomicInteger]], but not thread safe,
 * optimized for single threaded execution by an actor.
 */
class IntCounter(init: Int = 0) {
  private var value = init

  @inline def get = value
  @inline def inc = {
    val result = value
    value += 1
    result
  }
}

/**
 * Encapsulates information about requests's subscribers and last received response.
 */
class CallRecord(val targetId: Int,
                 val method: DSAMethod,
                 val path: Option[String],
                 private var _origins: Set[Origin],
                 var lastResponse: Option[DSAResponse]) {

  def addOrigin(origin: Origin) = _origins += origin

  def removeOrigin(origin: Origin) = _origins -= origin

  def origins = _origins
}

/**
 * Stores lookups to retrieve request records by source and target RID/SID and paths,
 * where appropriate.
 */
class CallRegistry(nextId: Int = 1) {
  private var nextTargetId = new IntCounter(nextId)
  private val callsByOrigin = collection.mutable.Map.empty[Origin, CallRecord]
  private val callsByTargetId = collection.mutable.Map.empty[Int, CallRecord]
  private val callsByPath = collection.mutable.Map.empty[String, CallRecord]

  def createTargetId = nextTargetId.inc

  def saveLookup(origin: Origin, method: DSAMethod, path: Option[String] = None, lastResponse: Option[DSAResponse] = None) = {
    val targetId = createTargetId
    val record = new CallRecord(targetId, method, path, Set(origin), lastResponse)
    callsByOrigin(origin) = record
    callsByTargetId(targetId) = record
    path foreach (callsByPath(_) = record)
    targetId
  }

  def saveEmpty(method: DSAMethod) = {
    val targetId = createTargetId
    callsByTargetId(targetId) = new CallRecord(targetId, method, None, Set.empty, None)
    targetId
  }

  def lookupByPath(path: String) = callsByPath.get(path)

  def lookupByOrigin(origin: Origin) = callsByOrigin.get(origin)

  def lookupByTargetId(targetId: Int) = callsByTargetId.get(targetId)

  def addOrigin(origin: Origin, record: CallRecord) = {
    record.addOrigin(origin)
    callsByOrigin(origin) = record
  }

  def removeOrigin(origin: Origin) = callsByOrigin remove origin map { rec => rec.removeOrigin(origin); rec }

  def removeLookup(record: CallRecord) = {
    record.origins foreach callsByOrigin.remove
    record.path foreach callsByPath.remove
    callsByTargetId.remove(record.targetId)
  }

  def info = s"Origin Lookups: ${callsByOrigin.size}, Target Lookups: ${callsByTargetId.size} Path Lookups: ${callsByPath.size}"
}