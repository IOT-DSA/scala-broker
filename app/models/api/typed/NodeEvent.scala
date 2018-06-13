package models.api.typed

import akka.actor.typed.ActorRef
import models.RequestEnvelope
import models.rpc.DSAValue._

sealed trait NodeEvent extends Serializable

sealed trait MgmtEvent extends NodeEvent

object MgmtEvent {
  final case class DisplayNameChanged(name: Option[String]) extends MgmtEvent
  final case class ValueChanged(value: DSAVal) extends MgmtEvent
  final case class AttributesChanged(attributes: DSAMap) extends MgmtEvent

  final case class ChildAdded(name: String, state: InitState) extends MgmtEvent
  final case class ChildRemoved(name: String) extends MgmtEvent
  final case object ChildrenRemoved extends MgmtEvent
}

sealed trait DSAEvent extends NodeEvent

object DSAEvent {
  final case class RequestsProcessed(env: RequestEnvelope) extends DSAEvent
}