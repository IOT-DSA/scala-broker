package models.sdk

import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue.{DSAMap, DSAVal}

/**
  * The internal state of the node.
  */
case class NodeState(value: Option[DSAVal],
                     action: Option[NodeAction],
                     attributes: DSAMap,
                     configs: DSAMap,
                     children: Set[String]) {

  val valueType: DSAValueType = configs.valueType
  val profile: String = configs.profile
  val displayName: Option[String] = configs.displayName
}

/**
  * Predefined states.
  */
object NodeState {
  /**
    * An empty state, assigned to a node initially.
    */
  val Empty = NodeState(None, None, Map.empty, Map.empty, Set.empty)
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