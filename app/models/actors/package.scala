package models

import scala.util.matching.Regex

/**
 * Types and utility functions for actors.
 */
package object actors {

  /**
   * Interpolates strings to produce RegEx.
   */
  implicit class RegexContext(val sc: StringContext) extends AnyVal {
    def r = new Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
  }
  
  /**
   * Resolves the target link path from the request path.
   */
  def resolveLinkPath(cfg: Settings)(path: String) = path match {
    case "/downstream"                          => cfg.Paths.Root
    case r"/downstream/(\w+)$responder(/.*)?$_" => s"/downstream/$responder"
    case "/upstream"                            => cfg.Paths.Root
    case r"/upstream/(\w+)$broker(/.*)?$_"      => s"/upstream/$broker"
    case _                                      => splitPath(path)._1 
  }
  
  /**
   * Splits a path into a (node, attribute/config) pair. If the path does not represents
   * an attribute or config, the second element of the returned tuple will be None.
   */
  def splitPath(path: String): (String, Option[String]) = path.split("/").lastOption collect {
    case s if s.startsWith("@") || s.startsWith("$") => (path.dropRight(s.size + 1), Some(s))
  } getOrElse Tuple2(path, None)
  
  /**
   * Returns `true` if the path represents an attribute.
   */
  def isAttribute(path: String) = path matches ".*/@[^/@]+"
  
  /**
   * Returns `true` if the path represents a config.
   */
  def isConfig(path: String) = path matches ".*/\\$\\$?[^/\\$]+"
}