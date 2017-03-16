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
    case r"/sys(/.*)?$_"                        => cfg.Paths.Sys
    case r"/users(/.*)?$_"                      => cfg.Paths.Users
    case "/downstream"                          => cfg.Paths.Downstream
    case r"/downstream/(\w+)$responder(/.*)?$_" => s"/downstream/$responder"
    case "/upstream"                            => cfg.Paths.Upstream
    case r"/upstream/(\w+)$broker(/.*)?$_"      => s"/upstream/$broker"
    case _                                      => trimPath(path)
  }
  
  /**
   * Strips the trailing attribute or config name from the path.
   */
  private def trimPath(path: String) = {
  	val elements = path.split("/")
  	elements.lastOption.flatMap(_.headOption) match {
  		case Some(ch) if ch == '$' || ch == '@' => elements.dropRight(1).mkString("/")
  		case _ => path
  	}
  }                   
}