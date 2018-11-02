package models.sdk

import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue.{DSAMap, DSAVal}
import models.sdk.NodeEvent._

/**
  * Defines the state supported by each tree node.
  */
trait NodeState {

  def value: Option[DSAVal]
  def withValue(newValue: Option[DSAVal]): NodeState

  def action: Option[NodeAction]
  def withAction(newAction: Option[NodeAction]): NodeState

  def attributes: DSAMap
  def withAttributes(attrs: DSAMap): NodeState

  def configs: DSAMap
  def withConfigs(cfgs: DSAMap): NodeState

  def children: Set[String]
  def withChildren(chldrn: Set[String]): NodeState

  def valueListeners: Set[ValueListener]
  def withValueListeners(listeners: Set[ValueListener]): NodeState

  def attributeListeners: Set[AttributeListener]
  def withAttributeListeners(listeners: Set[AttributeListener]): NodeState

  def configListeners: Set[ConfigListener]
  def withConfigListeners(listeners: Set[ConfigListener]): NodeState

  def childListeners: Set[ChildListener]
  def withChildListeners(listeners: Set[ChildListener]): NodeState

  val valueType: DSAValueType = configs.valueType
  val profile: String = configs.profile
  val displayName: Option[String] = configs.displayName

  private[sdk] def notifyValueListeners(event: ValueEvent) = valueListeners foreach (_ ! event)
  private[sdk] def notifyAttributeListeners(event: AttributeEvent) = attributeListeners foreach (_ ! event)
  private[sdk] def notifyConfigListeners(event: ConfigEvent) = configListeners foreach (_ ! event)
  private[sdk] def notifyChildListeners(event: ChildEvent) = childListeners foreach (_ ! event)
}

/**
  * Basic implementation of the node state.
  */
case class DefaultNodeState(value: Option[DSAVal] = None,
                             action: Option[NodeAction] = None,
                             attributes: DSAMap = Map.empty,
                             configs: DSAMap = Map.empty,
                             children: Set[String] = Set.empty,
                             valueListeners: Set[ValueListener] = Set.empty,
                             attributeListeners: Set[AttributeListener] = Set.empty,
                             configListeners: Set[ConfigListener] = Set.empty,
                             childListeners: Set[ChildListener] = Set.empty) extends NodeState {

  def withValue(newValue: Option[DSAVal]): NodeState = copy(value =  newValue)
  def withAction(newAction: Option[NodeAction]): NodeState = copy(action = newAction)
  def withAttributes(attrs: DSAMap): NodeState = copy(attributes = attrs)
  def withConfigs(cfgs: DSAMap): NodeState = copy(configs = cfgs)
  def withChildren(chldrn: Set[String]): NodeState = copy(children = chldrn)
  def withValueListeners(listeners: Set[ValueListener]): NodeState = copy(valueListeners = listeners)
  def withAttributeListeners(listeners: Set[AttributeListener]): NodeState = copy(attributeListeners = listeners)
  def withConfigListeners(listeners: Set[ConfigListener]): NodeState = copy(configListeners = listeners)
  def withChildListeners(listeners: Set[ChildListener]): NodeState = copy(childListeners = listeners)
}

/**
  * Predefined states.
  */
object DefaultNodeState {
  /**
    * An empty state, assigned to a node initially.
    */
  val Empty = DefaultNodeState()
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