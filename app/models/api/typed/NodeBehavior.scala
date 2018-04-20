package models.api.typed

import DSACommand._
import MgmtCommand._
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }

/**
 * Describes DSA node behavior.
 */
object NodeBehavior {

  type BHV[T] = PartialFunction[(ActorContext[T], T), Behavior[T]]

  /**
   * Handles management commands.
   */
  def mgmtHandler(state: DSANodeState): BHV[NodeCommand] = {
    case (_, GetState(ref)) =>
      ref ! state
      Behaviors.same
    case (_, SetDisplayName(name)) =>
      node(state.copy(displayName = name))
    case (_, SetValue(value)) =>
      node(state.copy(value = value))
    case (_, SetAttributes(attributes)) =>
      node(state.copy(attributes = attributes))
    case (_, PutAttribute(name, value)) =>
      node(state.copy(attributes = state.attributes + (name -> value)))
    case (_, RemoveAttribute(name)) =>
      node(state.copy(attributes = state.attributes - name))
    case (ctx, GetChildren(ref)) =>
      ref ! ctx.children.map(_.upcast[NodeCommand])
      Behaviors.same
    case (ctx, AddChild(childState, ref)) =>
      val child = ctx.spawn(node(childState), childState.name)
      ref ! child
      Behaviors.same
    case (ctx, RemoveChild(name)) =>
      ctx.child(name).foreach(_.upcast[NodeCommand] ! Stop)
      Behaviors.same
    case (_, Stop) =>
      Behaviors.stopped
  }

  /**
   * Handles DSA commands.
   */
  def dsaHandler(state: DSANodeState): BHV[NodeCommand] = {
    case (ctx, ProcessRequests(env)) =>
      Behaviors.same
  }

  /**
   * Builds node behavior.
   */
  def node(state: DSANodeState): Behavior[NodeCommand] = Behaviors.receivePartial {
    mgmtHandler(state) orElse dsaHandler(state)
  }
}