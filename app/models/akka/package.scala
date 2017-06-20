package models

import scala.util.matching.Regex
import models.rpc.DSAValue._

/**
 * Types and utility functions for DSA actors.
 */
package object akka {

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
    case cfg.Paths.Downstream                   => cfg.Paths.Downstream
    case r"/downstream/(\w+)$responder(/.*)?$_" => cfg.Paths.Downstream + "/" + responder
    case cfg.Paths.Upstream                     => cfg.Paths.Upstream
    case r"/upstream/(\w+)$broker(/.*)?$_"      => cfg.Paths.Upstream + "/" + broker
    case _                                      => splitPath(path)._1 
  }
  
  /**
   * A tuple for $is->"node" config.
   */
  val IsNode = is("node")

  /**
   * Creates a tuple for `$is` config.
   */
  def is(str: String): (String, StringValue) = "$is" -> StringValue(str)

  /**
   * Builds a list of rows, each containing two values.
   */
  def rows(pairs: (String, DSAVal)*) = pairs map {
    case (key, value) => array(key, value)
  } toList  
}