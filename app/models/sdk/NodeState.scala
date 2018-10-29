package models.sdk

import models.rpc.DSAValue.{DSAMap, DSAVal}

/**
  * The internal state of the node.
  */
case class NodeState(displayName: Option[String],
                     value: Option[DSAVal],
                     action: Option[NodeAction],
                     attributes: DSAMap,
                     children: Set[String])

/**
  * Predefined states.
  */
object NodeState {
  /**
    * An empty state, assigned to a node initially.
    */
  val Empty = NodeState(None, None, None, Map.empty, Set.empty)
}

/**
  * Status reported by the node to the clients.
  */
case class NodeStatus(name: String,
                      parent: Option[NodeRef] = None,
                      displayName: Option[String] = None,
                      value: Option[DSAVal] = None,
                      invokable: Boolean = false,
                      attributes: DSAMap = Map.empty)

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
      state.displayName,
      state.value,
      state.action.isDefined,
      state.attributes)
}