package models.sdk

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
import akka.persistence.typed.scaladsl.PersistentBehaviors.{CommandHandler, EventHandler}
import akka.persistence.typed.scaladsl.{Effect, PersistentBehaviors}
import models.sdk.NodeCommand._
import models.sdk.NodeEvent._

/**
  * Describes DSA node behavior.
  */
object NodeBehavior {

  import Effect._

  /**
    * Creates a node command handler.
    *
    * @param parent
    * @return
    */
  def commandHandler(parent: Option[NodeRef]): CommandHandler[NodeCommand, NodeEvent, NodeState] = {
    case (ctx, state, GetStatus(ref))        =>
      ref ! NodeStatus(ctx.self.path.name, parent, state)
      none
    case (_, _, SetDisplayName(displayName)) => persist(DisplayNameChanged(displayName))
    case (_, _, SetValue(value))             => persist(ValueChanged(value))
    case (a, b, SetAction(action))           => parent.map { _ =>
      persist[ActionChanged, NodeState](ActionChanged(action))
    }.getOrElse(throw new IllegalStateException("Root node cannot be an action"))
    case (ctx, _, Invoke(args, ref))         => none.thenRun { state =>
      import ctx.executionContext
      val action = state.action.getOrElse(throw new IllegalStateException("Node has no action"))
      val context = ActionContext(ctx.self, args)
      action.handler(context) foreach (ref ! _)
    }
    case (_, _, SetAttributes(attrs))        =>
      val attributes = attrs map {
        case (name, value) => ensurePrefix(name, "@") -> value
      }
      persist(AttributesChanged(attributes.toMap))
    case (_, _, PutAttribute(name, value))   => persist(AttributeAdded(ensurePrefix(name, "@"), value))
    case (_, _, RemoveAttribute(name))       => persist(AttributeRemoved(ensurePrefix(name, "@")))
    case (_, _, ClearAttributes)             => persist(AttributesChanged(Map.empty))
    case (ctx, _, GetChildren(ref))          =>
      ref ! ctx.children.map(_.upcast[NodeCommand])
      none
    case (ctx, _, GetChild(name, ref))       =>
      ref ! ctx.child(name).map(_.upcast[NodeCommand])
      none
    case (ctx, _, AddChild(name, ref))       => persist(ChildAdded(name)).thenRun { _ =>
      val child = ctx.spawn(node(name, Some(ctx.self)), name)
      if (ref != null)
        ref ! child
    }
    case (ctx, _, RemoveChild(name, ref))    => persist(ChildRemoved(name)).thenRun { _ =>
      ctx.child(name).foreach(_.upcast[NodeCommand] ! Stop)
      if (ref != null)
        ref ! Done
    }
    case (ctx, _, RemoveChildren(ref))       => persist(ChildrenRemoved).thenRun { _ =>
      ctx.children.foreach(_.upcast[NodeCommand] ! Stop)
      if (ref != null)
        ref ! Done
    }
    case (ctx, _, Stop)                      =>
      ctx.log.info("{}: node stopped", ctx.self.path)
      stop
  }

  /**
    * Node event handler.
    */
  val eventHandler: EventHandler[NodeState, NodeEvent] = {
    case (state, DisplayNameChanged(name))    => state.copy(displayName = Some(name))
    case (state, ValueChanged(value))         => state.copy(value = value)
    case (state, ActionChanged(action))       => state.copy(action = Some(action))
    case (state, AttributesChanged(attrs))    => state.copy(attributes = attrs)
    case (state, AttributeAdded(name, value)) => state.copy(attributes = state.attributes + (name -> value))
    case (state, AttributeRemoved(name))      => state.copy(attributes = state.attributes - name)
    case (state, ChildAdded(name))            => state.copy(children = state.children + name)
    case (state, ChildRemoved(name))          => state.copy(children = state.children - name)
    case (state, ChildrenRemoved)             => state.copy(children = Set.empty)
  }

  /**
    * Creates a node behavior.
    *
    * @param name
    * @param parent
    * @return
    */
  def node(name: String, parent: Option[NodeRef]): Behavior[NodeCommand] =
    Behaviors.setup { ctx =>
      ctx.log.info("{}: node created", ctx.self.path)
      PersistentBehaviors.receive(
        persistenceId = parent.map(_.path.toStringWithoutAddress).getOrElse("/") + name,
        emptyState = NodeState.Empty,
        commandHandler = commandHandler(parent),
        eventHandler = eventHandler
      ).onRecoveryCompleted { (ctx, state) =>
        ctx.log.debug("{}: recovery complete, {} children to spawn", ctx.self.path, state.children.size)
        state.children foreach { childName =>
          ctx.spawn(node(childName, Some(ctx.self)), childName)
        }
      }
    }

  /**
    * Creates a new actor system realizing the `NodeBehavior`.
    */
  def createActorSystem(name: String) = ActorSystem(node(name, None), name)

  /**
    * If `str` does not start with `prefix`, adds it to the beginning of it.
    *
    * @param str
    * @param prefix
    * @return
    */
  private def ensurePrefix(str: String, prefix: String) = if (str.startsWith(prefix)) str else prefix + str
}