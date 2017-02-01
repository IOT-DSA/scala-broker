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
    case r"/data(/.*)?$_"                       => cfg.Paths.Data
    case r"/defs(/.*)?$_"                       => cfg.Paths.Defs
    case r"/sys(/.*)?$_"                        => cfg.Paths.Sys
    case r"/users(/.*)?$_"                      => cfg.Paths.Users
    case "/downstream"                          => cfg.Paths.Downstream
    case r"/downstream/(\w+)$responder(/.*)?$_" => s"/downstream/$responder"
    case "/upstream"                            => cfg.Paths.Upstream
    case r"/upstream/(\w+)$broker(/.*)?$_"      => s"/upstream/$broker"
    case _                                      => path
  }  
}