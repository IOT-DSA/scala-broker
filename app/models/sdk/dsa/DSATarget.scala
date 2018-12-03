package models.sdk.dsa

import akka.actor.ActorSelection
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import models.sdk._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Encapsulates a "target" of a DSA command, which can be a node or particular attribute/config inside the node.
  *
  * @param destination
  * @param item
  */
case class DSATarget(selection: ActorSelection, item: Option[DSATarget.Item]) {
  val isAttribute = item map (_.isAttribute) getOrElse false
  val isConfig = item map (_.isConfig) getOrElse false
  val isBare = !isAttribute && !isConfig

  /**
    * Resolves the actor selection and returns the actor of the specified type T.
    *
    * @param timeout
    * @param ec
    * @tparam T
    * @return
    */
  def resolve[T](implicit timeout: Timeout, ec: ExecutionContext): Future[ActorRef[T]] =
    selection.resolveOne().map(_.upcast: ActorRef[T])

  /**
    * Sends a message to this target.
    *
    * @param msg
    */
  def !(msg: Any) = selection ! msg
}

/**
  * Factory for [[DSATarget]] instances.
  */
object DSATarget {

  /**
    * Encapsulates an attribute or config.
    *
    * @param name
    * @param isAttribute
    */
  case class Item(name: String, isAttribute: Boolean) {
    val isConfig = !isAttribute
  }

  /**
    * Parses a DSA path and returns the target selection.
    *
    * @param ctx
    * @param dsaPath
    * @return
    */
  def parse(ctx: ActorContext[DSANodeCommand], dsaPath: String): DSATarget = {
    val parts = dsaPath.split("/")

    val name = parts.lastOption.getOrElse("")
    val (destination, item) = if (name.startsWith(CfgPrefix))
      (ctx.self.path / RootNode / parts.init, Some(Item(name, false)))
    else if (name.startsWith(AttrPrefix))
      (ctx.self.path / RootNode / parts.init, Some(Item(name, true)))
    else
      (ctx.self.path / RootNode / parts, None)

    DSATarget(ctx.system.toUntyped.actorSelection(destination), item)
  }
}
