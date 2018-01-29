package models

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.matching.Regex

import _root_.akka.actor.{ Actor, ActorRef }
import _root_.akka.pattern.{ ask => query }
import _root_.akka.routing._
import _root_.akka.util.Timeout
import models.akka.cluster.ShardedRoutee
import models.rpc.DSAValue.{ DSAVal, StringValue, array }
import models.util.SimpleCache

/**
 * Types and utility functions for DSA actors.
 */
package object akka {

  private val pathCache = new SimpleCache[String, String](100, 1, Some(10000L), Some(1 hour))

  /**
   * Adds convenience methods to akka Routee.
   */
  implicit class RichRoutee(val routee: Routee) extends AnyVal {
    /**
     * An alias for `send`.
     */
    def !(msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit = routee.send(msg, sender)

    /**
     * Sends a message and returns a future response casting it to the specified type `T`.
     */
    def ask(msg: Any)(implicit timeout: Timeout,
                      sender: ActorRef = Actor.noSender): Future[Any] = routee match {
      case ActorRefRoutee(ref)             => query(ref, msg, sender)(timeout).mapTo[Any]
      case ActorSelectionRoutee(selection) => selection.ask(msg)(timeout, sender).mapTo[Any]
      case r: ShardedRoutee                => r.ask(msg)
    }

    /**
     * An alias for `ask`.
     */
    def ?(msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender) = ask(msg)
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