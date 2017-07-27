package models.akka

import models.Origin
import models.rpc.DSAMethod
import models.rpc.DSAMethod.DSAMethod

/**
 * Request registry tied to RID.
 */
class RidRegistry {
  import RidRegistry._

  private val nextTargetId = new IntCounter(1)

  private var callsByTargetId = Map.empty[Int, LookupRecord]
  private var callsByOrigin = Map.empty[Origin, LookupRecord]
  private var callsByPath = Map.empty[String, LookupRecord]

  /**
   * Saves lookup record for LIST call.
   */
  def saveListLookup(path: String): Int = saveLookup(DSAMethod.List, None, Some(path))

  /**
   * Saves lookup record for Set, Remove or Invoke call.
   */
  def savePassthroughLookup(method: DSAMethod, origin: Origin): Int = {
    import DSAMethod._
    require(method == Set || method == Invoke || method == Remove, "Only SET, INVOKE and REMOVE allowed")
    saveLookup(method, Some(origin), None)
  }

  /**
   * Saves lookup record for SUBSCRIBE call.
   */
  def saveSubscribeLookup(origin: Origin): Int = saveLookup(DSAMethod.Subscribe, Some(origin), None)

  /**
   * Saves lookup record for UNSUBSCRIBE call.
   */
  def saveUnsubscribeLookup(origin: Origin): Int = saveLookup(DSAMethod.Unsubscribe, Some(origin), None)

  /**
   * Saves the lookup and returns the newly generated target RID.
   */
  private def saveLookup(method: DSAMethod, origin: Option[Origin], path: Option[String]): Int = {
    val tgtId = nextTargetId.inc
    val record = LookupRecord(method, tgtId, origin, path)

    callsByTargetId += tgtId -> record
    origin foreach (callsByOrigin += _ -> record)
    path foreach (callsByPath += _ -> record)

    tgtId
  }

  /**
   * Locates the call record by target RID (used by response handlers).
   */
  def lookupByTargetId(targetId: Int): Option[LookupRecord] = callsByTargetId.get(targetId)

  /**
   * Locates the call record by the request origin (applicable to passthrough calls,
   * though in fact used only to close streaming INVOKE requests.)
   */
  def lookupByOrigin(origin: Origin): Option[LookupRecord] = callsByOrigin.get(origin)

  /**
   * Locates the call record by the path (applicable to LIST calls only).
   */
  def lookupByPath(path: String): Option[LookupRecord] = callsByPath.get(path)

  /**
   * Removes the call record.
   */
  def removeLookup(record: LookupRecord) = {
    record.origin foreach (callsByOrigin -= _)
    record.path foreach (callsByPath -= _)
    callsByTargetId -= record.targetId
  }

  /**
   * Returns the number of targetId keys in the registry.
   */
  def targetIdCount = callsByTargetId.size

  /**
   * Returns the number of origin keys in the registry.
   */
  def originCount = callsByOrigin.size

  /**
   * Returns the number of path keys in the registry.
   */
  def pathCount = callsByPath.size

  /**
   * Returns brief diagnostic information for the registry.
   */
  def info = s"Origin Lookups: ${callsByOrigin.size}, Target Lookups: ${callsByTargetId.size}, Path Lookups: ${callsByPath.size}"
}

/**
 * Common definitions for [[RidRegistry]].
 */
object RidRegistry {
  /**
   * Encapsulates request information for lookups.
   */
  case class LookupRecord(method: DSAMethod, targetId: Int, origin: Option[Origin], path: Option[String])
}