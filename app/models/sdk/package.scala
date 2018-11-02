package models

import _root_.akka.actor.typed.ActorRef
import _root_.akka.actor.typed.scaladsl._
import _root_.akka.persistence.typed.scaladsl._
import models.api.DSAValueType
import models.api.DSAValueType.DSADynamic
import models.rpc.DSAValue.DSAMap
import models.rpc.ResponseMessage
import models.sdk.NodeEvent._

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

  val DefaultValueType = DSADynamic
  val DefaultProfile = "node"

  /**
    * Provides extractors for common configs.
    *
    * @param configs
    */
  implicit private[sdk] class RickConfigMap(val configs: DSAMap) extends AnyVal {

    def valueType = configs.get(ValueTypeCfg).map { vt =>
      DSAValueType.withName(vt.toString)
    }.getOrElse(DefaultValueType)

    def profile = configs.get(ProfileCfg).map(_.toString).getOrElse(DefaultProfile)

    def displayName = configs.get(DisplayCfg).map(_.toString)
  }

}