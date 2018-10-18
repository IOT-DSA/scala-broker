package models.akka.responder

import scala.collection.mutable.{Map => MutableMap}
import models.akka.{IntCounter, LookupSidRemoved, LookupSidRestoreProcess, LookupSidSaved, PartOfPersistenceBehavior}
import SidRegistry.SidRegistryState
import models.akka.IntCounter.IntCounterState

/**
 * Request registry tied to SID.
 */
class SidRegistry(persistenceBehavior: PartOfPersistenceBehavior, var state: SidRegistryState) {

  private val targetSidCounter: IntCounter = new IntCounter(state.targetSids)
  /**
    * Returns the newly generated target SID before lookup saving.
    */
  def nextTgtId: Int = targetSidCounter.inc

  /**
   * Saves the lookup and persist this event.
   */
  def saveLookup(path: String, tgtId: Int): Unit = {
    persistenceBehavior.persist(LookupSidSaved(path, tgtId)) { event =>
      persistenceBehavior.log.debug("{}: persisting {}", persistenceBehavior.ownId, event)
      addLookup(event.path, event.tgtId)
      persistenceBehavior.onPersist
    }
  }

  private def addLookup(path: String, tgtSid: Int)= {
    state.sidByPath += path -> tgtSid
    state.pathBySid += tgtSid -> path
  }

  /**
   * Locates the SID by the path.
   */
  def lookupByPath(path: String): Option[Int] = state.sidByPath.get(path)

  /**
   * Removes the lookup.
   */
  def removeLookup(tgtId: Int): Unit = {
    persistenceBehavior.persist(LookupSidRemoved(tgtId)) { event =>
      persistenceBehavior.log.debug("{}: persisting {}", persistenceBehavior.ownId, event)
      internalRemoveLookup(event.tgtId)
      persistenceBehavior.onPersist
    }
  }

  private def internalRemoveLookup(tgtId: Int): MutableMap[Int, String] = {
    val path = state.pathBySid(tgtId)
    state.sidByPath -= path
    state.pathBySid -= tgtId
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
    assume(state.sidByPath.size == state.pathBySid.size, "Map sizes do not match")
    state.pathBySid.size
  }

  /**
   * Returns brief diagnostic information for the registry.
   */
  def info = s"Target Lookups: ${state.pathBySid.size}, Path Lookups: ${state.sidByPath.size}"
}



object SidRegistry {

  case class SidRegistryState(targetSids: IntCounterState, pathBySid: MutableMap[Int, String], sidByPath: MutableMap[String, Int])

  def apply(partOfPersistenceBehavior: PartOfPersistenceBehavior): SidRegistry = {
    val state = SidRegistryState(IntCounterState(1, 1), MutableMap.empty[Int, String], MutableMap.empty[String, Int])
    new SidRegistry(partOfPersistenceBehavior, state)
  }

  def apply(partOfPersistenceBehavior: PartOfPersistenceBehavior, state: SidRegistryState) = new SidRegistry(partOfPersistenceBehavior, state)
}
