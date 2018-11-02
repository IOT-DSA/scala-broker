package models.sdk

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
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
  def nodeCmdHandler(parent: Option[NodeRef]): CmdH[NodeCommand, NodeEvent, NodeState] = {

    val attributeHandler: CmdH[NodeCommand, NodeEvent, NodeState] = {

      def persistAndNotify(event: AttributeEvent) =
        persist[AttributeEvent, NodeState](event).thenRun(state => state.notifyAttributeListeners(event))

      {
        case (_, _, SetAttributes(attrs))      =>
          val attributes = attrs map {
            case (name, value) => ensurePrefix(name, AttrPrefix) -> value
          }
          persistAndNotify(AttributesChanged(attributes.toMap))
        case (_, _, PutAttribute(name, value)) => persistAndNotify(AttributeAdded(ensurePrefix(name, AttrPrefix), value))
        case (_, _, RemoveAttribute(name))     => persistAndNotify(AttributeRemoved(ensurePrefix(name, AttrPrefix)))
        case (_, _, ClearAttributes)           => persistAndNotify(AttributesChanged(Map.empty))
      }
    }

    val configHandler: CmdH[NodeCommand, NodeEvent, NodeState] = {

      def persistAndNotify(event: ConfigEvent) =
        persist[ConfigEvent, NodeState](event).thenRun(state => state.notifyConfigListeners(event))

      {
        case (_, _, SetConfigs(attrs))       =>
          val configs = attrs map {
            case (name, value) => ensurePrefix(name, CfgPrefix) -> value
          }
          persistAndNotify(ConfigsChanged(configs.toMap))
        case (_, _, PutConfig(name, value))  => persistAndNotify(ConfigAdded(ensurePrefix(name, CfgPrefix), value))
        case (_, _, RemoveConfig(name))      => persistAndNotify(ConfigRemoved(ensurePrefix(name, CfgPrefix)))
        case (_, _, ClearConfigs)            => persistAndNotify(ConfigsChanged(Map.empty))
        case (_, _, SetDisplayName(display)) => persistAndNotify(ConfigAdded(DisplayCfg, display))
        case (_, _, SetValueType(vType))     => persistAndNotify(ConfigAdded(ValueTypeCfg, vType))
        case (_, _, SetProfile(profile))     => persistAndNotify(ConfigAdded(ProfileCfg, profile))
      }
    }

    val childHandler: CmdH[NodeCommand, NodeEvent, NodeState] = {

      def persistAndNotify(event: ChildEvent) =
        persist[ChildEvent, NodeState](event).thenRun(state => state.notifyChildListeners(event))

      {
        case (ctx, _, GetChildren(ref))       =>
          ref ! ctx.children.map(_.upcast[NodeCommand])
          none
        case (ctx, _, GetChild(name, ref))    =>
          ref ! ctx.child(name).map(_.upcast[NodeCommand])
          none
        case (ctx, _, AddChild(name, ref))    =>
          persistAndNotify(ChildAdded(name)).thenRun { state =>
            val child = ctx.spawn(node(name, Some(ctx.self)), name)
            if (ref != null)
              ref ! child
          }
        case (ctx, _, RemoveChild(name, ref)) =>
          persistAndNotify(ChildRemoved(name)).thenRun { state =>
            ctx.child(name).foreach(_.upcast[NodeCommand] ! Stop)
            if (ref != null)
              ref ! Done
          }
        case (ctx, _, RemoveChildren(ref))    =>
          persistAndNotify(ChildrenRemoved).thenRun { state =>
            ctx.children.foreach(_.upcast[NodeCommand] ! Stop)
            if (ref != null)
              ref ! Done
          }
      }
    }

    val listenerHandler: CmdH[NodeCommand, NodeEvent, NodeState] = {
      case (_, _, AddValueListener(ref))        => persist(ValueListenerAdded(ref))
      case (_, _, AddAttributeListener(ref))    => persist(AttributeListenerAdded(ref))
      case (_, _, AddConfigListener(ref))       => persist(ConfigListenerAdded(ref))
      case (_, _, AddChildListener(ref))        => persist(ChildListenerAdded(ref))
      case (_, _, RemoveValueListener(ref))     => persist(ValueListenerRemoved(ref))
      case (_, _, RemoveAttributeListener(ref)) => persist(AttributeListenerRemoved(ref))
      case (_, _, RemoveConfigListener(ref))    => persist(ConfigListenerRemoved(ref))
      case (_, _, RemoveChildListener(ref))     => persist(ChildListenerRemoved(ref))
      case (_, _, RemoveAllValueListeners)      => persist(ValueListenersRemoved)
      case (_, _, RemoveAllAttributeListeners)  => persist(AttributeListenersRemoved)
      case (_, _, RemoveAllConfigListeners)     => persist(ConfigListenersRemoved)
      case (_, _, RemoveAllChildListeners)      => persist(ChildListenersRemoved)
    }

    attributeHandler orElse configHandler orElse childHandler orElse listenerHandler orElse {
      case (ctx, state, GetStatus(ref)) =>
        ref ! NodeStatus(ctx.self.path.name, parent, state)
        none
      case (_, _, SetValue(value))      =>
        val event = ValueChanged(value)
        persist(event).thenRun { state =>
          state.notifyValueListeners(event)
        }
      case (_, _, SetAction(action))    => parent.map { _ =>
        persist[ActionChanged, NodeState](ActionChanged(action))
      }.getOrElse(throw new IllegalStateException("Root node cannot be an action"))
      case (ctx, _, Invoke(args, ref))  => none.thenRun { state =>
        import ctx.executionContext
        val action = state.action.getOrElse(throw new IllegalStateException("Node has no action"))
        val context = ActionContext(ctx.self, args)
        action.handler(context) foreach (ref ! _)
      }
      case (ctx, _, Stop)               =>
        ctx.log.info("{}: node stopped", ctx.self.path)
        stop
    }
  }

  /**
    * Node event handler.
    */
  val nodeEventHandler: EvtH[NodeState, NodeEvent] = {
    case (state, ValueChanged(value))           => state.withValue(value)
    case (state, ActionChanged(action))         => state.withAction(Some(action))
    case (state, AttributesChanged(attrs))      => state.withAttributes(attrs)
    case (state, AttributeAdded(name, value))   => state.withAttributes(state.attributes + (name -> value))
    case (state, AttributeRemoved(name))        => state.withAttributes(state.attributes - name)
    case (state, ConfigsChanged(attrs))         => state.withConfigs(attrs)
    case (state, ConfigAdded(name, value))      => state.withConfigs(state.configs + (name -> value))
    case (state, ConfigRemoved(name))           => state.withConfigs(state.configs - name)
    case (state, ChildAdded(name))              => state.withChildren(state.children + name)
    case (state, ChildRemoved(name))            => state.withChildren(state.children - name)
    case (state, ChildrenRemoved)               => state.withChildren(Set.empty)
    case (state, ValueListenerAdded(ref))       => state.withValueListeners(state.valueListeners + ref)
    case (state, AttributeListenerAdded(ref))   => state.withAttributeListeners(state.attributeListeners + ref)
    case (state, ConfigListenerAdded(ref))      => state.withConfigListeners(state.configListeners + ref)
    case (state, ChildListenerAdded(ref))       => state.withChildListeners(state.childListeners + ref)
    case (state, ValueListenerRemoved(ref))     => state.withValueListeners(state.valueListeners - ref)
    case (state, AttributeListenerRemoved(ref)) => state.withAttributeListeners(state.attributeListeners - ref)
    case (state, ConfigListenerRemoved(ref))    => state.withConfigListeners(state.configListeners - ref)
    case (state, ChildListenerRemoved(ref))     => state.withChildListeners(state.childListeners - ref)
    case (state, ValueListenersRemoved)         => state.withValueListeners(Set.empty)
    case (state, AttributeListenersRemoved)     => state.withAttributeListeners(Set.empty)
    case (state, ConfigListenersRemoved)        => state.withConfigListeners(Set.empty)
    case (state, ChildListenersRemoved)         => state.withChildListeners(Set.empty)
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
      PersistentBehaviors.receive[NodeCommand, NodeEvent, NodeState](
        persistenceId = ctx.self.path.toStringWithoutAddress,
        emptyState = DefaultNodeState.Empty,
        commandHandler = (ctx, state, cmd) => nodeCmdHandler(parent)((ctx, state, cmd)),
        eventHandler = (state, event) => nodeEventHandler((state, event))
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