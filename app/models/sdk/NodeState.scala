package models.sdk

import models.rpc.DSAValue.{DSAMap, DSAVal}

/**
  * The internal state of the node.
  */
case class NodeState(displayName: Option[String],
                     value: Option[DSAVal],
                     attributes: DSAMap,
                     children: Set[String])

/**
  * Predefined states.
  */
object NodeState {
  /**
    * An empty state, assigned to a node initially.
    */
  val Empty = NodeState(None, None, Map.empty, Set.empty)
}

/**
  * Status reported by the node to the clients.
  */
case class NodeStatus(parent: Option[NodeRef],
                      name: String,
                      displayName: Option[String],
                      value: Option[DSAVal],
                      attributes: DSAMap)

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
      parent,
      name,
      state.displayName,
      state.value,
      state.attributes)
}