package models.akka

import scala.util.matching.Regex

import akka.actor.{ ActorContext, ActorRef }
import models.Settings
import models.rpc.DSAValue.{ DSAVal, StringValue, array }
import models.splitPath

/**
 * Types and utility functions for DSA clustered actors.
 */
package object cluster {
  import models.Settings.Paths._

  /* cluster node roles */
  val FrontendRole = "frontend"
  val BackendRole = "backend"
  
  /**
   * Sends a message to an actor using its DSA link path.
   */
  def dsaSend(to: String, msg: Any)(implicit context: ActorContext, sender: ActorRef) = {
    if (to == Downstream)
      context.actorSelection("/user/backend") ! msg
    else if (to startsWith Downstream) {
      val linkName = to drop Downstream.size + 1
      new ShardedDSLinkProxy(linkName)(context.system) tell msg
    }
    else {
      context.actorSelection("/user/" + Settings.Nodes.Root + to) ! msg
    }
  }

  /**
   * Interpolates strings to produce RegEx.
   */
  implicit class RegexContext(val sc: StringContext) extends AnyVal {
    def r = new Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
  }

  /**
   * Resolves the target link path from the request path.
   */
  def resolveLinkPath(path: String) = path match {
    case Settings.Paths.Downstream              => Settings.Paths.Downstream
    case r"/downstream/(\w+)$responder(/.*)?$_" => Settings.Paths.Downstream + "/" + responder
    case Settings.Paths.Upstream                => Settings.Paths.Upstream
    case r"/upstream/(\w+)$broker(/.*)?$_"      => Settings.Paths.Upstream + "/" + broker
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