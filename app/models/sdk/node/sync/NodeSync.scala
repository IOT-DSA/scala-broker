package models.sdk.node.sync

import akka.stream.scaladsl.Source
import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue.{DSAMap, DSAVal}
import models.sdk.node.NodeEvent.{AttributeEvent, ChildEvent, ConfigEvent, ValueEvent}
import models.sdk.node.{ActionResult, NodeAction, NodeStatus}

import scala.concurrent.Future

/**
  * The synchronous version of Node API.
  */
trait NodeSync {

  def status: NodeStatus

  def value: Option[DSAVal]
  def value_=(v: Option[DSAVal]): Unit

  def displayName: Option[String]
  def displayName_=(str: String): Unit

  def valueType: DSAValueType
  def valueType_=(vt: DSAValueType): Unit

  def profile: String
  def profile_=(prf: String): Unit

  def action: Option[NodeAction]
  def action_=(a: NodeAction): Unit
  def invoke(args: DSAMap): Future[ActionResult]

  def attributes: DSAMap
  def attributes_=(attrs: DSAMap): Unit
  def putAttribute(name: String, value: DSAVal): Unit
  def removeAttribute(name: String): Unit
  def clearAttributes(): Unit

  def configs: DSAMap
  def configs_=(cfgs: DSAMap): Unit
  def putConfig(name: String, value: DSAVal): Unit
  def removeConfig(name: String): Unit
  def clearConfigs(): Unit

  def children: Map[String, NodeSync]
  def addChild(name: String): NodeSync
  def removeChild(name: String): Unit
  def removeChildren(): Unit

  def valueEvents: Source[ValueEvent, _]
  def attributeEvents: Source[AttributeEvent, _]
  def configEvents: Source[ConfigEvent, _]
  def childEvents: Source[ChildEvent, _]
}
