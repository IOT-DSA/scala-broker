package models.sdk

import models.rpc.DSAValue._

/**
  * Events processed by a node.
  */
sealed trait NodeEvent extends Serializable

/**
  * Available node events.
  */
object NodeEvent {

  final case class DisplayNameChanged(name: String) extends NodeEvent
  final case class ValueChanged(value: Option[DSAVal]) extends NodeEvent
  final case class ActionChanged(action: NodeAction) extends NodeEvent

  final case class AttributeAdded(name: String, value: DSAVal) extends NodeEvent
  final case class AttributeRemoved(name: String) extends NodeEvent
  final case class AttributesChanged(attributes: DSAMap) extends NodeEvent

  final case class ChildAdded(name: String) extends NodeEvent
  final case class ChildRemoved(name: String) extends NodeEvent
  final case object ChildrenRemoved extends NodeEvent
}