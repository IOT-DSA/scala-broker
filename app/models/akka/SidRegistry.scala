package models.akka

/**
 * Request registry tied to SID.
 */
class SidRegistry {
  private val targetSids = new IntCounter(1)

  private var pathBySid = Map.empty[Int, String]
  private var sidByPath = Map.empty[String, Int]

  /**
   * Saves the lookup and returns the newly generated SID.
   */
  def saveLookup(path: String): Int = {
    val tgtSid = targetSids.inc
    sidByPath += path -> tgtSid
    pathBySid += tgtSid -> path
    tgtSid
  }

  /**
   * Locates the SID by the path.
   */
  def lookupByPath(path: String): Option[Int] = sidByPath.get(path)

  /**
   * Removes the lookup.
   */
  def removeLookup(targetSid: Int) = {
    val path = pathBySid(targetSid)
    sidByPath -= path
    pathBySid -= targetSid
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