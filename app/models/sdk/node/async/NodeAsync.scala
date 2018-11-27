package models.sdk.node.async

import akka.stream.scaladsl.Source
import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue.{DSAMap, DSAVal}
import models.sdk.node.NodeEvent.{AttributeEvent, ChildEvent, ConfigEvent, ValueEvent}
import models.sdk.node.{ActionResult, NodeAction, NodeStatus}

import scala.concurrent.Future

/**
  * The asynchronous version of Node API.
  */
trait NodeAsync {

  def status: Future[NodeStatus]

  def value: Future[Option[DSAVal]]
  def value_=(v: Option[DSAVal]): Unit

  def displayName: Future[Option[String]]
  def displayName_=(str: String): Unit

  def valueType: Future[DSAValueType]
  def valueType_=(vt: DSAValueType): Unit

  def profile: Future[String]
  def profile_=(prf: String): Unit

  def action: Future[Option[NodeAction]]
  def action_=(a: NodeAction): Unit
  def invoke(args: DSAMap): Future[ActionResult]

  def attributes: Future[DSAMap]
  def attributes_=(attrs: DSAMap): Unit
  def putAttribute(name: String, value: DSAVal): Unit
  def removeAttribute(name: String): Unit
  def clearAttributes(): Unit

  def configs: Future[DSAMap]
  def configs_=(cfgs: DSAMap): Unit
  def putConfig(name: String, value: DSAVal): Unit
  def removeConfig(name: String): Unit
  def clearConfigs(): Unit

  def children: Future[Map[String, NodeAsync]]
  def addChild(name: String): Future[NodeAsync]
  def removeChild(name: String): Future[Unit]
  def removeChildren(): Future[Unit]

  def valueEvents: Source[ValueEvent, _]
  def attributeEvents: Source[AttributeEvent, _]
  def configEvents: Source[ConfigEvent, _]
  def childEvents: Source[ChildEvent, _]
}
