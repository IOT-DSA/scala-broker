package models.sdk.dsa

import models.sdk.node.NodeEvent._
import models.sdk.node.NodeStatus
import models.sdk.{DSAListener, NodeRef}

/**
  * Encapsulates a subscription source - the actor and its subscription id (RID/SID).
  *
  * @param listener remote actor waiting for the updates.
  * @param id       RID/SID associated with the listener.
  */
case class Origin(listener: DSAListener, id: Int)

/**
  * Encapsulates node information used as a source for the initial (full) LIST update.
  *
  * @param node
  * @param status
  * @param children
  */
final case class ListInfo(node: NodeRef, status: NodeStatus, children: Map[String, NodeStatus]) {

  def apply(event: AttributeEvent): ListInfo = event match {
    case AttributeAdded(name, value) => copy(status = status.plusAttribute(name, value))
    case AttributeRemoved(name)      => copy(status = status.minusAttribute(name))
    case AttributesChanged(attrs)    => copy(status = status.withAttributes(attrs))
  }

  def apply(event: ConfigEvent): ListInfo = event match {
    case ConfigAdded(name, value) => copy(status = status.plusConfig(name, value))
    case ConfigRemoved(name)      => copy(status = status.minusConfig(name))
    case ConfigsChanged(attrs)    => copy(status = status.withConfigs(attrs))
  }

  def apply(event: ChildEvent): ListInfo = event match {
    case ChildAdded(name)   => copy(children = children + (name -> newNodeStatus(name)))
    case ChildRemoved(name) => copy(children = children - name)
    case ChildrenRemoved    => copy(children = Map.empty)
  }
}

/**
  * Represents a LIST subscription.
  *
  * @param listInfo
  * @param origins
  */
case class ListSubscription(listInfo: Option[ListInfo] = None, origins: Set[Origin] = Set.empty) {

  def withListInfo(info: ListInfo) = copy(listInfo = Some(info))

  def addOrigin(origin: Origin) = copy(origins = this.origins + origin)

  def withAttributeEvent(event: AttributeEvent) = copy(listInfo = listInfo.map(_ (event)))

  def withConfigEvent(event: ConfigEvent) = copy(listInfo = listInfo.map(_ (event)))

  def withChildEvent(event: ChildEvent) = copy(listInfo = listInfo.map(_ (event)))
}

/**
  * Factory for [[ListSubscription]] instances.
  */
object ListSubscription {
  def apply(origin: Origin): ListSubscription = apply(origins = Set(origin))
}

/**
  * DSA Node state, storing RID and SID subscriptions.
  *
  * @param rids
  */
case class DSANodeState(rids: Map[String, ListSubscription] = Map.empty)

/**
  * Predefined DSA node states.
  */
object DSANodeState {
  val Empty = DSANodeState()
}