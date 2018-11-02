package models.sdk

import models.rpc.DSAValue._

/**
  * Events processed by a node.
  */
trait NodeEvent extends Serializable

/**
  * Available node events.
  */
object NodeEvent {

  final case class ActionChanged(action: NodeAction) extends NodeEvent

  sealed trait ValueEvent extends NodeEvent
  final case class ValueChanged(value: Option[DSAVal]) extends ValueEvent

  sealed trait AttributeEvent extends NodeEvent
  final case class AttributeAdded(name: String, value: DSAVal) extends AttributeEvent
  final case class AttributeRemoved(name: String) extends AttributeEvent
  final case class AttributesChanged(attributes: DSAMap) extends AttributeEvent

  sealed trait ConfigEvent extends NodeEvent
  final case class ConfigAdded(name: String, value: DSAVal) extends ConfigEvent
  final case class ConfigRemoved(name: String) extends ConfigEvent
  final case class ConfigsChanged(attributes: DSAMap) extends ConfigEvent

  sealed trait ChildEvent extends NodeEvent
  final case class ChildAdded(name: String) extends ChildEvent
  final case class ChildRemoved(name: String) extends ChildEvent
  final case object ChildrenRemoved extends ChildEvent

  sealed trait ListenerEvent extends NodeEvent
  final case class ValueListenerAdded(ref: ValueListener) extends ListenerEvent
  final case class AttributeListenerAdded(ref: AttributeListener) extends ListenerEvent
  final case class ConfigListenerAdded(ref: ConfigListener) extends ListenerEvent
  final case class ChildListenerAdded(ref: ChildListener) extends ListenerEvent
  final case class ValueListenerRemoved(ref: ValueListener) extends ListenerEvent
  final case class AttributeListenerRemoved(ref: AttributeListener) extends ListenerEvent
  final case class ConfigListenerRemoved(ref: ConfigListener) extends ListenerEvent
  final case class ChildListenerRemoved(ref: ChildListener) extends ListenerEvent
  final case object ValueListenersRemoved extends ListenerEvent
  final case object AttributeListenersRemoved extends ListenerEvent
  final case object ConfigListenersRemoved extends ListenerEvent
  final case object ChildListenersRemoved extends ListenerEvent
}