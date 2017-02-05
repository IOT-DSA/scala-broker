package models.kafka

import org.apache.kafka.streams.state.KeyValueStore

import models.rpc.{ DSARequest, DSAResponse }
import models.Origin
import org.apache.kafka.streams.processor.ProcessorContext
import org.apache.kafka.streams.kstream.KStreamBuilder
import scala.util.control.NonFatal
import org.apache.kafka.streams.errors.ProcessorStateException

/**
 * Generates an increasing number sequence per key.
 */
class IdGenerator(store: KeyValueStore[String, Integer], init: Int = 0) {

  /**
   * Returns the last generated value or `init` if it has not been set.
   */
  def get(key: String): Int = Option(store.get(key)) map (_.intValue) getOrElse init

  /**
   * Returns the last generated value or `init` if it has not been set and increments the counter.
   */
  def inc(key: String): Int = {
    val value = get(key)
    store.put(key, value + 1)
    value
  }

  /**
   * Removes the generator key from the store.
   */
  def remove(key: String): Unit = store.delete(key)
}

/**
 * Encapsulates information about requests's subscribers and last received response.
 */
case class CallRecord(targetId: Int,
                      path: Option[String] = None,
                      origins: Set[Origin] = Set.empty,
                      lastResponse: Option[DSAResponse] = None) {

  def withResponse(rsp: DSAResponse) = copy(lastResponse = Some(rsp))
  def addOrigin(origin: Origin) = copy(origins = this.origins + origin)
  def removeOrigin(origin: Origin) = copy(origins = this.origins - origin)
}

/**
 * Stores lookup information for RIDs and SIDs.
 */
class CallRegistry(ids: KeyValueStore[String, Integer],
                   callsByTargetId: KeyValueStore[String, CallRecord],
                   callsByOrigin: KeyValueStore[String, CallRecord],
                   callsByPath: KeyValueStore[String, CallRecord]) {

  private val idGen = new IdGenerator(ids, 1)

  def saveLookup(target: String, origin: Origin, path: Option[String] = None,
                 lastResponse: Option[DSAResponse] = None) = {
    val targetId = createTargetId(target)
    val record = CallRecord(targetId, path, Set(origin), lastResponse)
    updateLookup(target, record)
    targetId
  }

  def saveEmpty(target: String) = {
    val targetId = createTargetId(target)
    callsByTargetId.put(targetIdKey(target, targetId), new CallRecord(targetId, None, Set.empty, None))
    targetId
  }

  def lookupByTargetId(target: String, targetId: Int) =
    Option(callsByTargetId.get(targetIdKey(target, targetId)))

  def lookupByPath(target: String, path: String) = Option(callsByPath.get(pathKey(target, path)))

  def updateLookup(target: String, record: CallRecord) = {
    callsByTargetId.put(targetIdKey(target, record.targetId), record)
    record.path foreach (p => callsByPath.put(pathKey(target, p), record))
    record.origins foreach (o => callsByOrigin.put(originKey(target, o), record))
  }

  def removeOrigin(target: String, origin: Origin) =
    Option(callsByOrigin.delete(originKey(target, origin))) map { rec =>
      updateLookup(target, rec.removeOrigin(origin))
      rec
    }

  def removeLookup(target: String, record: CallRecord) = {
    callsByTargetId.delete(targetIdKey(target, record.targetId))
    record.path foreach (p => callsByPath.delete(pathKey(target, p)))
    record.origins foreach (o => callsByOrigin.delete(originKey(target, o)))
    callsByOrigin.flush
  }

  private def createTargetId = idGen.inc _

  private def targetIdKey(target: String, targetId: Int) = target + ":" + targetId

  private def pathKey(target: String, path: String) = target + ":" + path

  private def originKey(target: String, origin: Origin) =
    target + ":" + origin.source + ":" + origin.sourceId
}

/**
 * Factor for [[CallRegistry]] instances.
 */
object CallRegistry {

  /**
   * Creates a new builder for the
   */
  def apply(name: String) = new CallRegistryManager(name)

  /**
   * Encapsulates store initializing functions for the registry with the given name.
   */
  class CallRegistryManager(name: String) {
    private val idGenName = name + "IdGenerator"
    private val callsByTargetIdName = name + "CallsByTargetId"
    private val callsByOriginName = name + "CallsByOrigin"
    private val callsByPathName = name + "CallsByPath"

    val storeNames = List(idGenName, callsByTargetIdName, callsByOriginName, callsByPathName)

    def build(ctx: ProcessorContext) = {
      val ids = ctx.getKeyValueStore[String, Integer](idGenName)
      val callsByTargetId = ctx.getKeyValueStore[String, CallRecord](callsByTargetIdName)
      val callsByOrigin = ctx.getKeyValueStore[String, CallRecord](callsByOriginName)
      val callsByPath = ctx.getKeyValueStore[String, CallRecord](callsByPathName)
      new CallRegistry(ids, callsByTargetId, callsByOrigin, callsByPath)
    }

    def createStores(builder: KStreamBuilder) = {
      builder.addKeyValueStore[String, Integer](idGenName, false)
      builder.addKeyValueStore[String, CallRecord](callsByTargetIdName, false)
      builder.addKeyValueStore[String, CallRecord](callsByOriginName, false)
      builder.addKeyValueStore[String, CallRecord](callsByPathName, false)
    }
  }
}