package models.sdk.node.sync

import akka.actor.Scheduler
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorMaterializer
import akka.util.Timeout
import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue.{DSAMap, DSAVal}
import models.sdk.NodeRef
import models.sdk.node.async.NodeAsyncAkkaBase
import models.sdk.node.{ActionResult, NodeAction, NodeEvent, NodeStatus}

import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * Akka-based implementation of NodeSync.
  */
case class NodeSyncAkkaImpl(ref: NodeRef)(implicit timeout: Timeout, scheduler: Scheduler,
                                          ec: ExecutionContext, mat: ActorMaterializer)
  extends NodeAsyncAkkaBase(ref) with NodeSync {

  private implicit def block[T](future: Future[T]): T = Await.result(future, timeout.duration)

  override def status: NodeStatus = _status

  override def value: Option[DSAVal] = _value
  override def value_=(v: Option[DSAVal]): Unit = (_value = v)

  override def displayName: Option[String] = _displayName
  override def displayName_=(str: String): Unit = (_displayName = str)

  override def valueType: DSAValueType = _valueType
  override def valueType_=(vt: DSAValueType): Unit = (_valueType = vt)

  override def profile: String = _profile
  override def profile_=(prf: String): Unit = (_profile = prf)

  override def action: Option[NodeAction] = _action
  override def action_=(a: NodeAction): Unit = (_action = a)
  override def invoke(args: DSAMap): Future[ActionResult] = _invoke(args)

  override def attributes: DSAMap = _attributes
  override def attributes_=(attrs: DSAMap): Unit = (_attributes = attrs)
  override def putAttribute(name: String, value: DSAVal): Unit = _putAttribute(name, value)
  override def removeAttribute(name: String): Unit = _removeAttribute(name)
  override def clearAttributes(): Unit = _clearAttributes()

  override def configs: DSAMap = _configs
  override def configs_=(cfgs: DSAMap): Unit = (_configs = cfgs)
  override def putConfig(name: String, value: DSAVal): Unit = _putConfig(name, value)
  override def removeConfig(name: String): Unit = _removeConfig(name)
  override def clearConfigs(): Unit = _clearConfigs()

  override def children: Map[String, NodeSync] = _children.map(_.mapValues(createNode))
  override def addChild(name: String): NodeSync = _addChild(name).map(createNode)
  override def removeChild(name: String): Unit = _removeChild(name)
  override def removeChildren(): Unit = _removeChildren()

  override def valueEvents: Source[NodeEvent.ValueEvent, _] = _valueEvents
  override def attributeEvents: Source[NodeEvent.AttributeEvent, _] = _attributeEvents
  override def configEvents: Source[NodeEvent.ConfigEvent, _] = _configEvents
  override def childEvents: Source[NodeEvent.ChildEvent, _] = _childEvents

  private def createNode(ref: NodeRef) = NodeSyncAkkaImpl(ref)
}
