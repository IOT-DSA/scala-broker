package models.akka.responder

import scala.collection.Map
import models.akka.IntCounter
import persistence._
import SidRegistry.SidRegistryState
import models.akka.IntCounter.IntCounterState

/**
 * Request registry tied to SID.
 */
class SidRegistry(val state: SidRegistryState,
                  val persistenceBehavior: PersistenceBehavior[LookupSidRestoreProcess, SidRegistryState]) {

  private val targetSidCounter: IntCounter = new IntCounter(state.targetSids)
  private var _state: SidRegistryState = state

  /**
    * Returns the newly generated target SID before lookup saving.
    */
  def nextTgtId(): Int = targetSidCounter.inc

  /**
   * Saves the lookup and persist this event.
   */
  def saveLookup(path: String, tgtId: Int): Unit = {
    persistenceBehavior.persist(LookupSidSaved(path, tgtId), _state) { _ => addLookup(path, tgtId) }
  }

  private def addLookup(path: String, tgtSid: Int): Unit = {
    _state = _state.copy(sidByPath = _state.sidByPath + (path -> tgtSid),
                         pathBySid = _state.pathBySid + (tgtSid -> path))
  }

  /**
   * Locates the SID by the path.
   */
  def lookupByPath(path: String): Option[Int] = _state.sidByPath get path

  /**
   * Removes the lookup.
   */
  def removeLookup(tgtId: Int): Unit = {
    persistenceBehavior.persist(LookupSidRemoved(tgtId), state) { _ => internalRemoveLookup(tgtId) }
  }

  private def internalRemoveLookup(tgtId: Int): Unit = {
    val path = _state.pathBySid(tgtId)
    _state = _state.copy(sidByPath = _state.sidByPath - path,
                         pathBySid = _state.pathBySid - tgtId)
  }

  def restoreSidRegistry(event: LookupSidRestoreProcess) = event match {
    case e: LookupSidSaved =>
      targetSidCounter.inc(e.tgtId)
      addLookup(e.path, e.tgtId)
    case e: LookupSidRemoved =>
      internalRemoveLookup(e.tgtId)
  }

  /**
   * Returns the number of entries in the registry.
   */
  def size: Int = {
    assume(_state.sidByPath.size == _state.pathBySid.size, "Map sizes do not match")
    state.pathBySid.size
  }

  /**
   * Returns brief diagnostic information for the registry.
   */
  def info = s"Target Lookups: ${state.pathBySid.size}, Path Lookups: ${state.sidByPath.size}"
}



object SidRegistry {

  case class SidRegistryState(targetSids: IntCounterState, pathBySid: Map[Int, String], sidByPath: Map[String, Int])

  object SidRegistryState {
    def empty(): SidRegistryState = SidRegistryState(IntCounterState(1, 1), Map.empty[Int, String], Map.empty[String, Int])
  }

  def apply(state: SidRegistryState, persistenceBehavior: PersistenceBehavior[LookupSidRestoreProcess, SidRegistryState]) = {
    new SidRegistry(state, persistenceBehavior)
  }
}
