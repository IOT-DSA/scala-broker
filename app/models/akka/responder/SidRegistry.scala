package models.akka.responder

import models.akka.{IntCounter, LookupSidRemoved, LookupSidRestoreProcess, LookupSidSaved, PartOfPersistenceBehavior}

/**
 * Request registry tied to SID.
 */
class SidRegistry(persistenceBehavior: PartOfPersistenceBehavior) {
  private val targetSids = new IntCounter(1)

  private var pathBySid = Map.empty[Int, String]
  private var sidByPath = Map.empty[String, Int]

  /**
    * Returns the newly generated target SID before lookup saving.
    */
  def nextTgtId: Int = targetSids.inc

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

  private def addLookup(path: String, tgtSid: Int) = {
    sidByPath += path -> tgtSid
    pathBySid += tgtSid -> path
  }

  /**
   * Locates the SID by the path.
   */
  def lookupByPath(path: String): Option[Int] = sidByPath.get(path)

  /**
   * Removes the lookup.
   */
  def removeLookup(tgtId: Int) = {
    persistenceBehavior.persist(LookupSidRemoved(tgtId)) { event =>
      persistenceBehavior.log.debug("{}: persisting {}", persistenceBehavior.ownId, event)
      internalRemoveLookup(event.tgtId)
      persistenceBehavior.onPersist
    }
  }

  private def internalRemoveLookup(tgtId: Int) = {
    val path = pathBySid(tgtId)
    sidByPath -= path
    pathBySid -= tgtId
  }

  def restoreSidRegistry(event: LookupSidRestoreProcess) = event match {
    case e: LookupSidSaved =>
      targetSids.inc(e.tgtId)
      addLookup(e.path, e.tgtId)
    case e: LookupSidRemoved =>
      internalRemoveLookup(e.tgtId)
  }

  /**
   * Returns the number of entries in the registry.
   */
  def size = {
    assume(sidByPath.size == pathBySid.size, "Map sizes do not match")
    pathBySid.size
  }

  /**
   * Returns brief diagnostic information for the registry.
   */
  def info = s"Target Lookups: ${pathBySid.size}, Path Lookups: ${sidByPath.size}"
}
