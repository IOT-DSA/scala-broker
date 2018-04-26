package models.api.typed

import DSACommand._
import MgmtCommand._
import akka.Done
import akka.actor.typed.{ActorSystem, Behavior}
import akka.persistence.typed.scaladsl.PersistentBehaviors
import akka.persistence.typed.scaladsl.Effect

/**
 * Describes DSA node behavior.
 */
object NodeBehavior {

//  type BHV[T] = PartialFunction[(ActorContext[T], T), Behavior[T]]
  type BHV[T, E, H] = PersistentBehaviors.CommandHandler[T, E, H]

//  private def commandHandler: BHV[NodeCommand, MgmtEvent, DSANodeState] = PersistentBehaviors.CommandHandler.byState {
//    case state =>
//      print("State from byState: " + state)
//      mgmtCommandHandler()
//  }

  /**
    * Handles management commands.
    */
  private def mgmtCommandHandler: BHV[NodeCommand, MgmtEvent, DSANodeState] = {
    case (ctx, state, GetState(ref)) =>
      ref ! state
      Effect.none
    case (_, _, SetDisplayName(name)) =>
      Effect.persist(DisplayNameChanged(name))
    case (_, _, SetValue(value)) =>
      Effect.persist(ValueChanged(value))
    case (_, _, SetAttributes(attributes)) =>
      Effect.persist(AttributesChanged(attributes))
    case (_, _, PutAttribute(name, value)) =>
      Effect.persist(AttributeAdded(name, value))
    case (_, _, RemoveAttribute(name)) =>
      // should we persist removed event here ?
      Effect.persist(AttributeRemoved(name))
    case (ctx, _, GetChildren(ref)) =>
      ref ! ctx.children.map(_.upcast[NodeCommand])
      Effect.none
    case (ctx, _, AddChild(childState, ref)) =>
      val child = ctx.spawn(node(childState), childState.name)
      ref ! child
      // do persist this child state, so-so solution anyway
      child ! PersistState(childState)
      Effect.none
    case (ctx, _, RemoveChild(name)) =>
      // should we persist removed event here ?
      ctx.child(name).foreach(_.upcast[NodeCommand] ! Stop)
      Effect.none
    case (_, _, PersistState(st)) =>
      Effect.persist(StatePersisted(st))
    case (_, _, Stop) =>
      println("STOPPED")
      Effect.stop
  }

  private def mgmtEventHandler(state: DSANodeState, event: MgmtEvent): DSANodeState =
    event match {
      case DisplayNameChanged(name) =>
        val newState = state.copy(displayName = name)
        println("EVENT DisplayNameChanged: " + state + " --> " + newState)
        newState
      case ValueChanged(value) =>
        val newState = state.copy(value = value)
        println("EVENT ValueChanged: " + state + " --> " + newState)
        newState
      case AttributesChanged(attributes) =>
        val newState = state.copy(attributes = attributes)
        println("EVENT AttributesChanged: " + state + " --> " + newState)
        newState
      case AttributeAdded(name, value) =>
        val newState = state.copy(attributes = state.attributes + (name -> value))
        println("EVENT AttributeAdded: " + state + " --> " + newState)
        newState
      case AttributeRemoved(name) =>
        val newState = state.copy(attributes = state.attributes - name)
        println("EVENT AttributeRemoved: " + state + " --> " + newState)
        newState
      case StatePersisted(st) =>
        val newState = st
        println("EVENT StatePersisted: " + state + " --> " + newState)
        newState

//      case ChildRemoved(name) =>
//        state
    }

  /**
   * Handles DSA commands.
   */
  private def dsaCommandHandler: BHV[NodeCommand, MgmtEvent, DSANodeState] = {
    case (ctx, state, ProcessRequests(env)) =>
      println("WARNING: not supported yet")
      Effect.unhandled
  }

  private def commandHandler: BHV[NodeCommand, MgmtEvent, DSANodeState] = {
    case (ctx, state, command: MgmtCommand) => mgmtCommandHandler(ctx, state, command)
    case (ctx, state, command: DSACommand) => dsaCommandHandler(ctx, state, command)
  }

  /**
   * Builds node behavior.
   */
  def node(state: DSANodeState = DSANodeState.empty): Behavior[NodeCommand] =
    PersistentBehaviors.receive[NodeCommand, MgmtEvent, DSANodeState](
      // works when displayName is unique only
      persistenceId = "node-id-1-" + state.displayName,
      initialState = state,
      commandHandler = commandHandler,
      eventHandler = mgmtEventHandler
    ).onRecoveryCompleted { (ctx, state) =>
      println("CALL --> onRecoveryCompleted, state: " + state)
    }
//      .snapshotEvery(1)
}