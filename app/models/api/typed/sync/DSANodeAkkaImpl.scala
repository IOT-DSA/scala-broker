package models.api.typed.sync

import scala.concurrent.{ Await, ExecutionContext, Future }

import akka.actor.Scheduler
import akka.util.Timeout
import models.api.typed.NodeRef
import models.api.typed.async.DSANodeAsyncAkkaBase
import models.rpc.DSAValue.{ DSAMap, DSAVal }
import models.api.typed.InitState

/**
 * Akka-based implementation of DSANode.
 */
case class DSANodeAkkaImpl(ref: NodeRef)(implicit timeout: Timeout,
                                    scheduler: Scheduler, ec: ExecutionContext)
  extends DSANodeAsyncAkkaBase(ref) with DSANode {

  private implicit def block[T](future: Future[T]): T = Await.result(future, timeout.duration)

  def name: String = nameImpl
  def parent: Option[DSANode] = parentImpl.map(_.map(createNode))

  def displayName: Option[String] = displayNameImpl
  def displayName_=(s: Option[String]): Unit = { displayNameImpl = s }

  def value: DSAVal = valueImpl
  def value_=(v: DSAVal): Unit = { valueImpl = v }

  def attributes: DSAMap = attributesImpl
  def attributes_=(attrs: DSAMap): Unit = { attributesImpl = attrs }
  def addAttributes(cfg: (String, DSAVal)*): Unit = addAttributesImpl(cfg: _*)
  def removeAttribute(name: String): Unit = removeAttributeImpl(name)
  def clearAttributes(): Unit = clearAttributesImpl

  def children: Map[String, DSANode] = childrenImpl.map(_.mapValues(createNode))
  def addChild(name: String, state: InitState): DSANode = addChildImpl(name, state).map(createNode)
  def removeChild(name: String): Unit = removeChildImpl(name)
  def removeChildren(): Unit = removeChildrenImpl
  
  private def createNode(ref: NodeRef) = DSANodeAkkaImpl(ref)
}