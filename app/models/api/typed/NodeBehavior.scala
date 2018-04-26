package models.api.typed

import DSACommand.ProcessRequests
import MgmtCommand._
import akka.actor.typed.{ ActorSystem, Behavior }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }

/**
 * Describes DSA node behavior.
 */
object NodeBehavior {

  type BHV[T] = PartialFunction[(ActorContext[T], T), Behavior[T]]

  /**
   * Handles management commands.
   */
  def mgmtHandler(state: NodeStateInternal): BHV[NodeCommand] = {
    case (ctx, GetState(ref)) =>
      ref ! state.toPublic(ctx.self.path.name)
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
    case (_, ClearAttributes) =>
      node(state.copy(attributes = Map.empty))
    case (ctx, GetChildren(ref)) =>
      ref ! ctx.children.map(_.upcast[NodeCommand])
      Behaviors.same
    case (ctx, AddChild(name, initState, ref)) =>
      val child = ctx.spawn(node(initState.toInternal(Some(ctx.self))), name)
      ref ! child
      Behaviors.same
    case (ctx, RemoveChild(name)) =>
      ctx.child(name).foreach(_.upcast[NodeCommand] ! Stop)
      Behaviors.same
    case (ctx, RemoveChildren) =>
      ctx.children.foreach(_.upcast[NodeCommand] ! Stop)
      Behaviors.same
    case (_, Stop) =>
      Behaviors.stopped
  }

  /**
   * Handles DSA commands.
   */
  def dsaHandler(state: NodeStateInternal): BHV[NodeCommand] = {
    case (ctx, ProcessRequests(env)) =>
      Behaviors.same
  }

  /**
   * Builds node behavior.
   */
  def node(state: NodeStateInternal): Behavior[NodeCommand] = Behaviors.receivePartial {
    mgmtHandler(state) orElse dsaHandler(state)
  }

  /**
   * Creates a new actor system realizing the `NodeBehavior`.
   */
  def createActorSystem(name: String, state: InitState) = ActorSystem(node(state.toInternal(None)), name)
}