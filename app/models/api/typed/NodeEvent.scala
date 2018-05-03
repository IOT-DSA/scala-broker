package models.api.typed

import models.rpc.DSAValue.{DSAMap, DSAVal}

sealed trait NodeEvent

sealed trait MgmtEvent extends NodeEvent

final case class DisplayNameChanged(name: String) extends MgmtEvent
final case class ValueChanged(value: DSAVal) extends MgmtEvent
final case class AttributesChanged(attributes: DSAMap) extends MgmtEvent
final case class AttributeAdded(name: String, value: DSAVal) extends MgmtEvent
final case class AttributeRemoved(name: String) extends MgmtEvent
//final case class StateChanged(state: DSANodeState) extends MgmtEvent
final case class ChildAdded(name: String) extends MgmtEvent
final case class ChildRemoved(name: String) extends MgmtEvent

sealed trait DSAEvent extends NodeEvent

final case class Stub(name: String) extends DSAEvent
