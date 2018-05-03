package models.api.typed

import DSACommand._
import MgmtCommand._
//import DSAEvent._
//import MgmtEvent._
import MgmtResponse._
import akka.actor.typed.ActorContext
import akka.actor.typed.{ActorSystem, Behavior}
import akka.persistence.typed.scaladsl.PersistentBehaviors
import akka.persistence.typed.scaladsl.Effect

/**
 * Describes DSA node behavior.
 */
object NodeBehavior {

//  type BHV[T] = PartialFunction[(ActorContext[T], T), Behavior[T]]
  type BHV[T, H, E] = PersistentBehaviors.CommandHandler[T, H, E]

//  private def commandHandlerGEN: BHV[NodeCommand, MgmtEvent, DSANodeState] = PersistentBehaviors.CommandHandler.byState {
//    case state if state.displayName == "" =>
//      println("State from byState [no displ name]: " + state)
//      initMgmtCommandHandler
//    case state if state.displayName != "" =>
//      println("State from byState [displ name here]: " + state)
//      mgmtCommandHandler
//
//  }

//  private def initMgmtCommandHandler: BHV[NodeCommand, MgmtEvent, DSANodeState] = {
//    case (_, _, PersistState(st)) =>
//      Effect.persist(StatePersisted(st))
//    case (_, _, Stop) =>
//      println("STOPPED-GEN")
//      Effect.stop
//  }

  /**
    * Handles management commands.
    */
  private def mgmtCommandHandler: BHV[NodeCommand, MgmtEvent, DSANodeState] = {
    case (_, state, GetState(ref)) =>
      ref ! GetStateResponse(state)
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
      Effect.persist(AttributeRemoved(name))
    case (ctx, _, GetChildren(ref)) =>
      ref ! ctx.children.map(_.upcast[NodeCommand])
      Effect.none
    case (ctx, _, AddChild(ref)) =>
      val nodeId = NodeId.getRandomNodeId
      Effect.persist(ChildAdded(nodeId)).andThen {
        val child = ctx.spawn(node(nodeId), nodeId)
//        child ! SetState(childState)
        ref ! AddChildResponse(child)
      }
    case (ctx, _, RemoveChild(name)) =>
      Effect.persist(ChildRemoved(name)).andThen {
        ctx.child(name).foreach(_.upcast[NodeCommand] ! Stop)
      }
//    case (_, _, SetState(state)) =>
//      Effect.persist(StateChanged(state))
    case (_, _, Stop) =>
      println("STOPPED")
      Effect.stop
  }

  /**
    * Handles management events.
    */
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
//      case StateChanged(st) =>
//        val newState = st
//        println("EVENT StateChanged: " + state + " --> " + newState)
//        newState
      case ChildAdded(name) =>
        val newState = state.copy(childPersistenceIds = state.childPersistenceIds + name)
        println("EVENT ChildAdded: " + state + " --> " + newState)
        newState
      case ChildRemoved(name) =>
        val newState = state.copy(childPersistenceIds = state.childPersistenceIds - name)
        println("EVENT ChildRemoved: " + state + " --> " + newState)
        newState
    }

  /**
    * Handles DSA commands.
    */
  private def dsaCommandHandler: BHV[NodeCommand, MgmtEvent, DSANodeState] = {
    case (ctx, state, ProcessRequests(env)) =>
      println("WARNING: dsaCommandHandler is not supported yet")
      Effect.unhandled
  }

  /**
    * Handles DSA events.
    */
  private def dsaEventHandler(state: DSANodeState, event: DSAEvent): DSANodeState =
    event match {
      case Stub(name) =>
        println("WARNING: dsaEventHandler is not supported yet")
        state
    }

  private def commandHandler: BHV[NodeCommand, NodeEvent, DSANodeState] = {
    case (ctx, state, command: MgmtCommand) => mgmtCommandHandler(ctx, state, command)
    case (ctx, state, command: DSACommand) => dsaCommandHandler(ctx, state, command)
  }

  private def eventHandler(state: DSANodeState, event: NodeEvent): DSANodeState =
    event match {
      case event: MgmtEvent => mgmtEventHandler(state, event)
      case event: DSAEvent => dsaEventHandler(state, event)
    }

  /**
   * Builds node behavior.
   */
  def node(nodeId: String): Behavior[NodeCommand] =
    PersistentBehaviors.receive[NodeCommand, NodeEvent, DSANodeState](
      persistenceId = nodeId,
      initialState = DSANodeState.empty,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    ).onRecoveryCompleted { (ctx, state) =>
      println("CALL --> onRecoveryCompleted, state: " + state)
      for (id <- state.childPersistenceIds) {
        println(s"Trying to recover a child by id $id")
        ctx.spawn(node(nodeId = id), id)
      }
    }
//      .snapshotEvery(1)
}