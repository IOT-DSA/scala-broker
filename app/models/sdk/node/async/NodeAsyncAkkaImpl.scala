package models.sdk.node.async

import akka.actor.Scheduler
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorMaterializer
import akka.util.Timeout
import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue.{DSAMap, DSAVal}
import models.sdk.NodeRef
import models.sdk.node.{ActionResult, NodeAction, NodeEvent, NodeStatus}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Akka-based implementation of NodeAsync.
  */
case class NodeAsyncAkkaImpl(ref: NodeRef)(implicit timeout: Timeout, scheduler: Scheduler,
                                           ec: ExecutionContext, mat: ActorMaterializer)
  extends NodeAsyncAkkaBase(ref) with NodeAsync {

  override def status: Future[NodeStatus] = _status

  override def value: Future[Option[DSAVal]] = _value
  override def value_=(v: Option[DSAVal]): Unit = (_value = v)

  override def displayName: Future[Option[String]] = _displayName
  override def displayName_=(str: String): Unit = (_displayName = str)

  override def valueType: Future[DSAValueType] = _valueType
  override def valueType_=(vt: DSAValueType): Unit = (_valueType = vt)

  override def profile: Future[String] = _profile
  override def profile_=(prf: String): Unit = (_profile = prf)

  override def action: Future[Option[NodeAction]] = _action
  override def action_=(a: NodeAction): Unit = (_action = a)
  override def invoke(args: DSAMap): Future[ActionResult] = _invoke(args)

  override def attributes: Future[DSAMap] = _attributes
  override def attributes_=(attrs: DSAMap): Unit = (_attributes = attrs)
  override def putAttribute(name: String, value: DSAVal): Unit = _putAttribute(name, value)
  override def removeAttribute(name: String): Unit = _removeAttribute(name)
  override def clearAttributes(): Unit = _clearAttributes()

  override def configs: Future[DSAMap] = _configs
  override def configs_=(cfgs: DSAMap): Unit = (_configs = cfgs)
  override def putConfig(name: String, value: DSAVal): Unit = _putConfig(name, value)
  override def removeConfig(name: String): Unit = _removeConfig(name)
  override def clearConfigs(): Unit = _clearConfigs()

  override def children: Future[Map[String, NodeAsync]] = _children.map(_.mapValues(createNode))
  override def addChild(name: String): Future[NodeAsync] = _addChild(name).map(createNode)
  override def removeChild(name: String): Future[Unit] = _removeChild(name).map(_ => {})
  override def removeChildren(): Future[Unit] = _removeChildren().map(_ => {})

  override def valueEvents: Source[NodeEvent.ValueEvent, _] = _valueEvents
  override def attributeEvents: Source[NodeEvent.AttributeEvent, _] = _attributeEvents
  override def configEvents: Source[NodeEvent.ConfigEvent, _] = _configEvents
  override def childEvents: Source[NodeEvent.ChildEvent, _] = _childEvents

  private def createNode(ref: NodeRef) = NodeAsyncAkkaImpl(ref)
}
