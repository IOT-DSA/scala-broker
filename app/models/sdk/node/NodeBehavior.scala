package models.sdk.node

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
import akka.persistence.typed.scaladsl.{Effect, PersistentBehaviors}
import models.rpc.DSAValue.{DSAMap, DSAVal}
import models.sdk._

/**
  * Describes DSA node behavior.
  */
object NodeBehavior {

  import Effect._
  import NodeCommand._
  import NodeEvent._

  /**
    * Creates a node command handler.
    *
    * @param parent
    * @return
    */
  def nodeCmdHandler(parent: Option[NodeRef]): CmdH[NodeCommand, NodeEvent, NodeState] = {

    // attribute management
    val attributeHandler: CmdH[NodeCommand, NodeEvent, NodeState] = {

      def persistAndNotify(event: AttributeEvent) =
        persist[AttributeEvent, NodeState](event).thenRun(state => state.notifyAttributeListeners(event))

      {
        case (ctx, _, SetAttributes(attrs))      =>
          val attributes = attrs map[(String, DSAVal), DSAMap] {
            case (name, value) => ensurePrefix(name, AttrPrefix) -> value
          }
          ctx.log.info("{}: setting attributes to {}", ctx.self.path, attributes)
          persistAndNotify(AttributesChanged(attributes))
        case (ctx, _, PutAttribute(name, value)) =>
          val attrName = ensurePrefix(name, AttrPrefix)
          ctx.log.info("{}: adding attribute {} -> {}", ctx.self.path, attrName, value)
          persistAndNotify(AttributeAdded(attrName, value))
        case (ctx, _, RemoveAttribute(name))     =>
          val attrName = ensurePrefix(name, AttrPrefix)
          ctx.log.info("{}: removing attribute {}", ctx.self.path, attrName)
          persistAndNotify(AttributeRemoved(attrName))
        case (ctx, _, ClearAttributes)           =>
          ctx.log.info("{}: removing all attributes", ctx.self.path)
          persistAndNotify(AttributesChanged(Map.empty))
      }
    }

    // config management
    val configHandler: CmdH[NodeCommand, NodeEvent, NodeState] = {

      def persistAndNotify(event: ConfigEvent) =
        persist[ConfigEvent, NodeState](event).thenRun(state => state.notifyConfigListeners(event))

      {
        case (ctx, _, SetConfigs(attrs))       =>
          val configs = attrs map[(String, DSAVal), DSAMap] {
            case (name, value) => ensurePrefix(name, CfgPrefix) -> value
          }
          ctx.log.info("{}: setting configs to {}", ctx.self.path, configs)
          persistAndNotify(ConfigsChanged(configs))
        case (ctx, _, PutConfig(name, value))  =>
          val cfgName = ensurePrefix(name, CfgPrefix)
          ctx.log.info("{}: adding config {} -> {}", ctx.self.path, cfgName, value)
          persistAndNotify(ConfigAdded(cfgName, value))
        case (ctx, _, RemoveConfig(name))      =>
          val cfgName = ensurePrefix(name, CfgPrefix)
          ctx.log.info("{}: removing config {}", ctx.self.path, cfgName)
          persistAndNotify(ConfigRemoved(cfgName))
        case (ctx, _, ClearConfigs)            =>
          ctx.log.info("{}: removing all configs", ctx.self.path)
          persistAndNotify(ConfigsChanged(Map.empty))
        case (ctx, _, SetDisplayName(display)) =>
          ctx.log.info("{}: setting display name to {}", ctx.self.path, display)
          persistAndNotify(ConfigAdded(DisplayCfg, display))
        case (ctx, _, SetValueType(vType))     =>
          ctx.log.info("{}: setting value type to {}", ctx.self.path, vType)
          persistAndNotify(ConfigAdded(ValueTypeCfg, vType))
        case (ctx, _, SetProfile(profile))     =>
          ctx.log.info("{}: setting profile to {}", ctx.self.path, profile)
          persistAndNotify(ConfigAdded(ProfileCfg, profile))
      }
    }

    // child management
    val childHandler: CmdH[NodeCommand, NodeEvent, NodeState] = {

      def persistAndNotify(event: ChildEvent) =
        persist[ChildEvent, NodeState](event).thenRun(state => state.notifyChildListeners(event))

      {
        case (ctx, _, GetChildren(ref))       =>
          ctx.log.info("{}: children requested, returning {} entries", ctx.self.path, ctx.children.size)
          ref ! ctx.children.map(_.upcast[NodeCommand])
          none
        case (ctx, _, GetChild(name, ref))    =>
          val child = ctx.child(name)
          ctx.log.info("{}: child [{}] requested, returning {}", ctx.self.path, name, child)
          ref ! child.map(_.upcast[NodeCommand])
          none
        case (ctx, _, AddChild(name, ref))    =>
          ctx.log.info("{}: adding child [{}]", ctx.self.path, name)
          persistAndNotify(ChildAdded(name)).thenRun { state =>
            val child = ctx.spawn(node(Some(ctx.self)), name)
            if (ref != null)
              ref ! child
          }
        case (ctx, _, RemoveChild(name, ref)) =>
          ctx.log.info("{}: removing child [{}]", ctx.self.path, name)
          persistAndNotify(ChildRemoved(name)).thenRun { state =>
            ctx.child(name).foreach(_.upcast[NodeCommand] ! Stop)
            if (ref != null)
              ref ! Done
          }
        case (ctx, _, RemoveChildren(ref))    =>
          ctx.log.info("{}: removing all children", ctx.self.path)
          persistAndNotify(ChildrenRemoved).thenRun { _ =>
            ctx.children.foreach(_.upcast[NodeCommand] ! Stop)
            if (ref != null)
              ref ! Done
          }
      }
    }

    // event listener management
    val listenerHandler: CmdH[NodeCommand, NodeEvent, NodeState] = {
      case (ctx, _, AddValueListener(ref))        =>
        ctx.log.info("{}: adding value listener {}", ctx.self.path, ref)
        persist(ValueListenerAdded(ref))
      case (ctx, _, AddAttributeListener(ref))    =>
        ctx.log.info("{}: adding value listener {}", ctx.self.path, ref)
        persist(AttributeListenerAdded(ref))
      case (ctx, _, AddConfigListener(ref))       =>
        ctx.log.info("{}: adding config listener {}", ctx.self.path, ref)
        persist(ConfigListenerAdded(ref))
      case (ctx, _, AddChildListener(ref))        =>
        ctx.log.info("{}: adding child listener {}", ctx.self.path, ref)
        persist(ChildListenerAdded(ref))
      case (ctx, _, RemoveValueListener(ref))     =>
        ctx.log.info("{}: removing value listener {}", ctx.self.path, ref)
        persist(ValueListenerRemoved(ref))
      case (ctx, _, RemoveAttributeListener(ref)) =>
        ctx.log.info("{}: removing attribute listener {}", ctx.self.path, ref)
        persist(AttributeListenerRemoved(ref))
      case (ctx, _, RemoveConfigListener(ref))    =>
        ctx.log.info("{}: removing config listener {}", ctx.self.path, ref)
        persist(ConfigListenerRemoved(ref))
      case (ctx, _, RemoveChildListener(ref))     =>
        ctx.log.info("{}: removing child listener {}", ctx.self.path, ref)
        persist(ChildListenerRemoved(ref))
      case (ctx, _, RemoveAllValueListeners)      =>
        ctx.log.info("{}: removing all value listeners", ctx.self.path)
        persist(ValueListenersRemoved)
      case (ctx, _, RemoveAllAttributeListeners)  =>
        ctx.log.info("{}: removing all attribute listeners", ctx.self.path)
        persist(AttributeListenersRemoved)
      case (ctx, _, RemoveAllConfigListeners)     =>
        ctx.log.info("{}: removing all config listeners", ctx.self.path)
        persist(ConfigListenersRemoved)
      case (ctx, _, RemoveAllChildListeners)      =>
        ctx.log.info("{}: removing all child listeners", ctx.self.path)
        persist(ChildListenersRemoved)
    }

    attributeHandler orElse configHandler orElse childHandler orElse listenerHandler orElse {
      case (ctx, state, GetStatus(ref)) => none.thenRun { _ =>
        val status = NodeStatus(ctx.self.path.name, parent, state)
        ctx.log.info("{}: status requested, returning {}", ctx.self.path, status)
        ref ! status
      }
      case (ctx, _, SetValue(value))    =>
        ctx.log.info("{}: setting value to {}", ctx.self.path, value)
        val event = ValueChanged(value)
        persist(event).thenRun { state =>
          state.notifyValueListeners(event)
        }
      case (ctx, _, SetAction(action))  => parent.map { _ =>
        ctx.log.info("{}: setting action to {}", ctx.self.path, action)
        persist[ActionChanged, NodeState](ActionChanged(action))
      }.getOrElse(throw new IllegalStateException("Root node cannot be an action"))
      case (ctx, _, Invoke(args, ref))  => none.thenRun { state =>
        ctx.log.info("{}: invoking action with {}", ctx.self.path, args)
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
    * @param parent
    * @return
    */
  def node(parent: Option[NodeRef]): Behavior[NodeCommand] =
    Behaviors.setup { ctx =>
      ctx.log.info("{}: node created", ctx.self.path)
      PersistentBehaviors.receive[NodeCommand, NodeEvent, NodeState](
        persistenceId = ctx.self.path.toStringWithoutAddress,
        emptyState = NodeState.Empty,
        commandHandler = (ctx, state, cmd) => nodeCmdHandler(parent)((ctx, state, cmd)),
        eventHandler = (state, event) => nodeEventHandler((state, event))
      ).onRecoveryCompleted { (ctx, state) =>
        ctx.log.debug("{}: recovery complete, {} children to spawn", ctx.self.path, state.children.size)
        state.children foreach (childName => ctx.spawn(node(Some(ctx.self)), childName))
      }
    }

  /**
    * Creates a new actor system realizing the `NodeBehavior`.
    */
  def createActorSystem(name: String) = ActorSystem(node(None), name)

  /**
    * If `str` does not start with `prefix`, adds it to the beginning of it.
    *
    * @param str
    * @param prefix
    * @return
    */
  private def ensurePrefix(str: String, prefix: String) = if (str.startsWith(prefix)) str else prefix + str
}
