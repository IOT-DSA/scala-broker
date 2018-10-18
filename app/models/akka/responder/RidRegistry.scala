package models.akka.responder

import collection.mutable.{Map => MutableMap}
import models.Origin
import models.akka.IntCounter.IntCounterState
import models.akka.responder.RidRegistry.RidRegistryState
import models.akka.{IntCounter, LookupRidRemoved, LookupRidRestoreProcess, LookupRidSaved, PartOfPersistenceBehavior}
import models.rpc.DSAMethod
import models.rpc.DSAMethod.DSAMethod

/**
 * Request registry tied to RID.
 */
class RidRegistry(val persistenceBehavior: PartOfPersistenceBehavior, var state: RidRegistryState) {
  import models.akka.responder.RidRegistry.LookupRecord

  private val nextTargetId: IntCounter = new IntCounter(state.internalCounterState)

  /**
    * Returns the newly generated target RID before lookup saving.
    */
  def nextTgtId(): Int = nextTargetId.inc

  /**
    * Saves lookup record for LIST call.
    */
  def saveListLookup(path: String, tgtId: Int): Unit = saveLookup(DSAMethod.List, None, Some(path), tgtId)


  /**
   * Saves lookup record for Set, Remove or Invoke call.
   */
  def savePassthroughLookup(method: DSAMethod, origin: Origin, tgtId: Int): Unit = {
    import DSAMethod._
    require(method == Set || method == Invoke || method == Remove, "Only SET, INVOKE and REMOVE allowed")
    saveLookup(method, Some(origin), None, tgtId)
  }

  /**
   * Saves lookup record for SUBSCRIBE call.
   */
  def saveSubscribeLookup(origin: Origin, tgtId: Int): Unit = saveLookup(DSAMethod.Subscribe, Some(origin), None, tgtId)

  /**
   * Saves lookup record for UNSUBSCRIBE call.
   */
  def saveUnsubscribeLookup(origin: Origin, tgtId: Int): Unit = saveLookup(DSAMethod.Unsubscribe, Some(origin), None, tgtId)


  /**
   * Saves the lookup and persist this event.
   */
  private def saveLookup(method: DSAMethod, origin: Option[Origin], path: Option[String], tgtId: Int): Unit = {
    persistenceBehavior.persist(LookupRidSaved(method, origin, path, tgtId)) { event =>
      persistenceBehavior.log.debug("{}: persisting {}", persistenceBehavior.ownId, event)
      addLookup(event.method, event.origin, event.path, event.tgtId)
      persistenceBehavior.onPersist
    }
  }

  private def addLookup(method: DSAMethod, origin: Option[Origin], path: Option[String], tgtId: Int): Unit = {
    val record = LookupRecord(method, tgtId, origin, path)
    state.callsByTargetId += tgtId → record
    origin foreach (state.callsByOrigin += _ → record)
    path foreach (state.callsByPath += _ → record)
  }

  /**
   * Locates the call record by target RID (used by response handlers).
   */
  def lookupByTargetId(targetId: Int): Option[LookupRecord] = state.callsByTargetId.get(targetId)

  /**
   * Locates the call record by the request origin (applicable to passthrough calls,
   * though in fact used only to close streaming INVOKE requests.)
   */
  def lookupByOrigin(origin: Origin): Option[LookupRecord] = state.callsByOrigin.get(origin)

  /**
   * Locates the call record by the path (applicable to LIST calls only).
   */
  def lookupByPath(path: String): Option[LookupRecord] = state.callsByPath.get(path)

  /**
   * Removes the call record and persist this event.
   */
  def removeLookup(record: LookupRecord): Unit = {
    persistenceBehavior.persist(LookupRidRemoved(record)) { event =>
      persistenceBehavior.log.debug("{}: persisting {}", persistenceBehavior.ownId, event)
      internalRemoveLookup(event.record)
      persistenceBehavior.onPersist
    }
  }

  private def internalRemoveLookup(record: LookupRecord): Unit = {
    record.origin foreach (state.callsByOrigin -= _)
    record.path foreach (state.callsByPath -= _)
    state.callsByTargetId -= record.targetId
  }

  def restoreRidRegistry(event: LookupRidRestoreProcess): Unit = event match {
    case e: LookupRidSaved =>
      nextTargetId.inc(e.tgtId)
      addLookup(e.method, e.origin, e.path, e.tgtId)
    case e: LookupRidRemoved =>
      internalRemoveLookup(e.record)
  }

  /**
   * Returns the number of targetId keys in the registry.
   */
  def targetIdCount: Int = state.callsByTargetId.size

  /**
   * Returns the number of origin keys in the registry.
   */
  def originCount: Int = state.callsByOrigin.size

  /**
   * Returns the number of path keys in the registry.
   */
  def pathCount: Int = state.callsByPath.size

  /**
   * Returns brief diagnostic information for the registry.
   */
  def info = s"Origin Lookups: ${state.callsByOrigin.size}, Target Lookups: ${state.callsByTargetId.size}, Path Lookups: ${state.callsByPath.size}"
}

/**
 * Common definitions and methods for [[RidRegistry]]
 */
object RidRegistry {
  /**
   * Encapsulates request information for lookups.
   */
  case class LookupRecord(method: DSAMethod, targetId: Int, origin: Option[Origin], path: Option[String])
  case class RidRegistryState(internalCounterState: IntCounterState, callsByTargetId: MutableMap[Int, LookupRecord],
                              callsByOrigin: MutableMap[Origin, LookupRecord], callsByPath: MutableMap[String, LookupRecord])

  def apply(persistenceBehavior: PartOfPersistenceBehavior, state: RidRegistryState): RidRegistry = new RidRegistry(persistenceBehavior, state)

  def apply(persistenceBehavior: PartOfPersistenceBehavior): RidRegistry = {
    def state = RidRegistryState(IntCounterState(1, 1), MutableMap.empty[Int, LookupRecord],
                                 MutableMap.empty[Origin, LookupRecord], MutableMap.empty[String, LookupRecord])
    new RidRegistry(persistenceBehavior, state)
  }
}
