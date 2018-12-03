package models.sdk.node

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior, LogMarker}
import akka.persistence.typed.scaladsl.{Effect, PersistentBehaviors}
import models.rpc.DSAValue.{DSAMap, DSAVal}
import models.sdk._
import models.sdk.Implicits._

/**
  * Describes DSA node behavior.
  */
object NodeBehavior {

  import Effect._
  import NodeCommand._
  import NodeEvent._

  private implicit val marker = LogMarker("node")

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
          ctx.info("setting attributes to {}", attributes)
          persistAndNotify(AttributesChanged(attributes))
        case (ctx, _, PutAttribute(name, value)) =>
          val attrName = ensurePrefix(name, AttrPrefix)
          ctx.info("adding attribute {} -> {}", attrName, value)
          persistAndNotify(AttributeAdded(attrName, value))
        case (ctx, _, RemoveAttribute(name))     =>
          val attrName = ensurePrefix(name, AttrPrefix)
          ctx.info("removing attribute {}", attrName)
          persistAndNotify(AttributeRemoved(attrName))
        case (ctx, _, ClearAttributes)           =>
          ctx.info("removing all attributes")
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
          ctx.info("setting configs to {}", configs)
          persistAndNotify(ConfigsChanged(configs))
        case (ctx, _, PutConfig(name, value))  =>
          val cfgName = ensurePrefix(name, CfgPrefix)
          ctx.info("adding config {} -> {}", cfgName, value)
          persistAndNotify(ConfigAdded(cfgName, value))
        case (ctx, _, RemoveConfig(name))      =>
          val cfgName = ensurePrefix(name, CfgPrefix)
          ctx.info("removing config {}", cfgName)
          persistAndNotify(ConfigRemoved(cfgName))
        case (ctx, _, ClearConfigs)            =>
          ctx.info("removing all configs")
          persistAndNotify(ConfigsChanged(Map.empty))
        case (ctx, _, SetDisplayName(display)) =>
          ctx.info("setting display name to {}", display)
          persistAndNotify(ConfigAdded(DisplayCfg, display))
        case (ctx, _, SetValueType(vType))     =>
          ctx.info("setting value type to {}", vType)
          persistAndNotify(ConfigAdded(ValueTypeCfg, vType))
        case (ctx, _, SetProfile(profile))     =>
          ctx.info("setting profile to {}", profile)
          persistAndNotify(ConfigAdded(ProfileCfg, profile))
      }
    }

    // child management
    val childHandler: CmdH[NodeCommand, NodeEvent, NodeState] = {

      def persistAndNotify(event: ChildEvent) =
        persist[ChildEvent, NodeState](event).thenRun(state => state.notifyChildListeners(event))

      {
        case (ctx, _, GetChildren(ref))       =>
          ctx.debug("children requested, returning {} entries", ctx.children.size)
          ref ! ctx.children.map(_.upcast[NodeCommand])
          none
        case (ctx, _, GetChild(name, ref))    =>
          val child = ctx.child(name)
          ctx.debug("child [{}] requested, returning {}", name, child)
          ref ! child.map(_.upcast[NodeCommand])
          none
        case (ctx, _, AddChild(name, ref))    =>
          ctx.info("adding child [{}]", name)
          persistAndNotify(ChildAdded(name)).thenRun { _ =>
            val child = ctx.spawn(node(Some(ctx.self)), name)
            if (ref != null)
              ref ! child
          }
        case (ctx, _, RemoveChild(name, ref)) =>
          ctx.info("removing child [{}]", name)
          persistAndNotify(ChildRemoved(name)).thenRun { _ =>
            ctx.child(name) foreach ctx.stop
            if (ref != null)
              ref ! Done
          }
        case (ctx, _, RemoveChildren(ref))    =>
          ctx.info("removing all children")
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
        ctx.info("adding value listener {}", ref)
        persist(ValueListenerAdded(ref))
      case (ctx, _, AddAttributeListener(ref))    =>
        ctx.info("adding value listener {}", ref)
        persist(AttributeListenerAdded(ref))
      case (ctx, _, AddConfigListener(ref))       =>
        ctx.info("adding config listener {}", ref)
        persist(ConfigListenerAdded(ref))
      case (ctx, _, AddChildListener(ref))        =>
        ctx.info("adding child listener {}", ref)
        persist(ChildListenerAdded(ref))
      case (ctx, _, RemoveValueListener(ref))     =>
        ctx.info("removing value listener {}", ref)
        persist(ValueListenerRemoved(ref))
      case (ctx, _, RemoveAttributeListener(ref)) =>
        ctx.info("removing attribute listener {}", ref)
        persist(AttributeListenerRemoved(ref))
      case (ctx, _, RemoveConfigListener(ref))    =>
        ctx.info("removing config listener {}", ref)
        persist(ConfigListenerRemoved(ref))
      case (ctx, _, RemoveChildListener(ref))     =>
        ctx.info("removing child listener {}", ref)
        persist(ChildListenerRemoved(ref))
      case (ctx, _, RemoveAllValueListeners)      =>
        ctx.info("removing all value listeners")
        persist(ValueListenersRemoved)
      case (ctx, _, RemoveAllAttributeListeners)  =>
        ctx.info("removing all attribute listeners")
        persist(AttributeListenersRemoved)
      case (ctx, _, RemoveAllConfigListeners)     =>
        ctx.info("removing all config listeners")
        persist(ConfigListenersRemoved)
      case (ctx, _, RemoveAllChildListeners)      =>
        ctx.info("removing all child listeners")
        persist(ChildListenersRemoved)
    }

    // action management
    val actionHandler: CmdH[NodeCommand, NodeEvent, NodeState] = {
      case (ctx, state, GetAction(ref)) => none.thenRun { _ =>
        ctx.debug("action requested, returning {}", state.action)
        ref ! state.action
      }
      case (ctx, _, SetAction(action))  => parent.map { _ =>
        ctx.info("setting action to {}", action)
        persist[ActionChanged, NodeState](ActionChanged(action))
      }.getOrElse(throw new IllegalStateException("Root node cannot be an action"))
      case (ctx, _, Invoke(args, ref))  => none.thenRun { state =>
        ctx.info("invoking action with {}", args)
        import ctx.executionContext
        val action = state.action.getOrElse(throw new IllegalStateException("Node has no action"))
        val context = ActionContext(ctx.self, args)
        action.handler(context) foreach (ref ! _)
      }
    }

    // main handler
    attributeHandler orElse configHandler orElse childHandler orElse actionHandler orElse listenerHandler orElse {
      case (ctx, state, GetStatus(ref)) => none.thenRun { _ =>
        val status = NodeStatus(ctx.self.path.name, parent, state)
        ctx.debug("status requested, returning {}", status)
        ref ! status
      }
      case (ctx, _, SetValue(value))    =>
        ctx.info("setting value to {}", value)
        val event = ValueChanged(value)
        persist(event).thenRun { state =>
          state.notifyValueListeners(event)
        }
      case (ctx, _, Stop)               =>
        ctx.info("node stopped")
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
      ctx.info("node created")
      PersistentBehaviors.receive[NodeCommand, NodeEvent, NodeState](
        persistenceId = ctx.self.path.toStringWithoutAddress,
        emptyState = NodeState.Empty,
        commandHandler = (ctx, state, cmd) => nodeCmdHandler(parent)((ctx, state, cmd)),
        eventHandler = (state, event) => nodeEventHandler((state, event))
      ).onRecoveryCompleted { (ctx, state) =>
        ctx.debug("recovery complete, {} children to spawn", state.children.size)
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
