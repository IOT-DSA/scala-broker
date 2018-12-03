package models.sdk.dsa

import models.sdk.node.NodeEvent.{AttributeEvent, ChildEvent, ConfigEvent}

/**
  * DSA (top level) node events.
  */
sealed trait DSANodeEvent

/**
  * Available dsa node events.
  */
object DSANodeEvent {

  /* list */
  final case class ListSubscriptionCreated(path: String, origin: Origin) extends DSANodeEvent
  final case class ListOriginAdded(path: String, origin: Origin) extends DSANodeEvent
  final case class ListInfoDelivered(path: String, info: ListInfo) extends DSANodeEvent
  final case class AttributeEventDelivered(path: String, evt: AttributeEvent) extends DSANodeEvent
  final case class ConfigEventDelivered(path: String, evt: ConfigEvent) extends DSANodeEvent
  final case class ChildEventDelivered(path: String, evt: ChildEvent) extends DSANodeEvent
}