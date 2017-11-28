package models

import scala.util.matching.Regex

import models.rpc.DSAMessage
import models.rpc.DSAValue.{ DSAVal, StringValue, array }
import net.sf.ehcache.{ Cache, CacheManager, Element }

/**
 * Types and utility functions for DSA actors.
 */
package object akka {

  private val pathCache = {
    val cacheManager = CacheManager.getInstance
    cacheManager.addCache(new Cache("resolved_paths", 1000, false, false, 0, 60))
    cacheManager.getEhcache("resolved_paths")
  }

  /**
   * Interpolates strings to produce RegEx.
   */
  implicit class RegexContext(val sc: StringContext) extends AnyVal {
    def r = new Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
  }

  /**
   * Retrieves the target link from the cache or does path resolution.
   */
  def resolveLinkPath(path: String) = Option(pathCache.get(path)).map(_.getObjectValue.toString).getOrElse {
    val resolvedPath = doResolveLinkPath(path)
    val element = new Element(path, resolvedPath)
    pathCache.put(element)
    resolvedPath
  }

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

  /**
   * Outputs the message either as raw JSON or as Scala object, according to
   * `broker.logging.show.ws.payload` config.
   */
  def formatMessage(msg: DSAMessage) = if (Settings.Logging.ShowWebSocketPayload)
    play.api.libs.json.Json.toJson(msg).toString
  else
    msg.toString
}