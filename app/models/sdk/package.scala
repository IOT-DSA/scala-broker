package models

import _root_.akka.actor.typed.ActorRef
import _root_.akka.actor.typed.scaladsl._
import _root_.akka.persistence.typed.scaladsl._
import models.api.DSAValueType
import models.rpc.ResponseMessage
import models.sdk.node.NodeCommand
import models.sdk.node.NodeEvent.{AttributeEvent, ChildEvent, ConfigEvent, ValueEvent}

/**
  * Helper constants and methods for typed actors.
  */
package object sdk {
  type NodeRef = ActorRef[NodeCommand]
  type NodeRefs = Iterable[NodeRef]

  type CmdH[Command, Event, State] = PartialFunction[(ActorContext[Command], State, Command), Effect[Event, State]]
  type EvtH[State, Event] = PartialFunction[(State, Event), State]

  type ValueListener = ActorRef[ValueEvent]
  type AttributeListener = ActorRef[AttributeEvent]
  type ConfigListener = ActorRef[ConfigEvent]
  type ChildListener = ActorRef[ChildEvent]

  type DSAListener = ActorRef[ResponseMessage]

  val CfgPrefix = "$"
  val AttrPrefix = "@"

  val ValueTypeCfg = CfgPrefix + "type"
  val ProfileCfg = CfgPrefix + "is"
  val DisplayCfg = CfgPrefix + "name"

  val DefaultValueType = DSAValueType.DSADynamic
  val DefaultProfile = "node"
}