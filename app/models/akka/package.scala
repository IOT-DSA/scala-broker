package models

import scala.concurrent.duration.DurationInt
import scala.util.matching.Regex

import models.rpc.DSAValue.{ DSAVal, StringValue, array }
import models.util.SimpleCache

/**
 * Types and utility functions for DSA actors.
 */
package object akka {

  private val pathCache = new SimpleCache[String, String](100, 1, Some(10000L), Some(1 hour))

  /**
   * Interpolates strings to produce RegEx.
   */
  implicit class RegexContext(val sc: StringContext) extends AnyVal {
    def r = new Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
  }

  /**
   * Retrieves the target link from the cache or does path resolution.
   */
  def resolveLinkPath(path: String) = pathCache.getOrElseUpdate(path, doResolveLinkPath(path))

  /**
   * Resolves the target link path from the request path.
   */
  private def doResolveLinkPath(path: String) = path match {
    case Settings.Paths.Downstream => Settings.Paths.Downstream
    case r"/downstream/([\w\-]+)$responder(/.*)?$_" => Settings.Paths.Downstream + "/" + responder
    case Settings.Paths.Upstream => Settings.Paths.Upstream
    case r"/upstream/([\w\-]+)$broker(/.*)?$_" => Settings.Paths.Upstream + "/" + broker
    case _ => splitPath(path)._1
  }

  /**
   * A tuple for \$is->"node" config.
   */
  val IsNode = is("node")

  /**
   * Creates a tuple for \$is config.
   */
  def is(str: String): (String, StringValue) = "$is" -> StringValue(str)

  /**
   * Builds a list of rows, each containing two values.
   */
  def rows(pairs: (String, DSAVal)*) = pairs map {
    case (key, value) => array(key, value)
  } toList
}