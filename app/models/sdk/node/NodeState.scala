package models.sdk.node

import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue.{DSAMap, DSAVal}
import models.sdk.Implicits._
import models.sdk._
import models.sdk.node.NodeEvent._

/**
  * Defines the state supported by each tree node.
  */
case class NodeState(value: Option[DSAVal] = None,
                     action: Option[NodeAction] = None,
                     attributes: DSAMap = Map.empty,
                     configs: DSAMap = Map.empty,
                     children: Set[String] = Set.empty,
                     valueListeners: Set[ValueListener] = Set.empty,
                     attributeListeners: Set[AttributeListener] = Set.empty,
                     configListeners: Set[ConfigListener] = Set.empty,
                     childListeners: Set[ChildListener] = Set.empty) {

  def withValue(newValue: Option[DSAVal]): NodeState = copy(value = newValue)

  def withAction(newAction: Option[NodeAction]): NodeState = copy(action = newAction)

  def withAttributes(attrs: DSAMap): NodeState = copy(attributes = attrs)

  def withConfigs(cfgs: DSAMap): NodeState = copy(configs = cfgs)

  def withChildren(chldrn: Set[String]): NodeState = copy(children = chldrn)

  def withValueListeners(listeners: Set[ValueListener]): NodeState = copy(valueListeners = listeners)

  def withAttributeListeners(listeners: Set[AttributeListener]): NodeState = copy(attributeListeners = listeners)

  def withConfigListeners(listeners: Set[ConfigListener]): NodeState = copy(configListeners = listeners)

  def withChildListeners(listeners: Set[ChildListener]): NodeState = copy(childListeners = listeners)

  val valueType: DSAValueType = configs.valueType
  val profile: String = configs.profile
  val displayName: Option[String] = configs.displayName

  private[node] def notifyValueListeners(event: ValueEvent) = valueListeners foreach (_ ! event)

  private[node] def notifyAttributeListeners(event: AttributeEvent) = attributeListeners foreach (_ ! event)

  private[node] def notifyConfigListeners(event: ConfigEvent) = configListeners foreach (_ ! event)

  private[node] def notifyChildListeners(event: ChildEvent) = childListeners foreach (_ ! event)
}

/**
  * Predefined states.
  */
object NodeState {
  /**
    * An empty state, assigned to a node initially.
    */
  val Empty = NodeState()
}

/**
  * Status reported by the node to the clients.
  */
case class NodeStatus(name: String,
                      parent: Option[NodeRef] = None,
                      value: Option[DSAVal] = None,
                      invokable: Boolean = false,
                      attributes: DSAMap = Map.empty,
                      configs: DSAMap = Map.empty) {

  val valueType: DSAValueType = configs.valueType
  val profile: String = configs.profile
  val displayName: Option[String] = configs.displayName

  /**
    * The config map that guaranteed to have the entries defined by defaults (valueType, profile, displayName).
    */
  val fullConfigs = configs + (ValueTypeCfg -> (valueType: DSAVal)) + (ProfileCfg -> (profile: DSAVal))
}

/**
  * Factory for [[NodeStatus]] instances.
  */
object NodeStatus {

  /**
    * Constructs a new node status instance.
    *
    * @param name
    * @param parent
    * @param state
    * @return
    */
  def apply(name: String, parent: Option[NodeRef], state: NodeState): NodeStatus =
    NodeStatus(
      name,
      parent,
      state.value,
      state.action.isDefined,
      state.attributes,
      state.configs)
}