package models.api.typed.async

import scala.concurrent.{ ExecutionContext, Future }

import akka.actor.Scheduler
import akka.util.Timeout
import models.api.typed.NodeRef
import models.rpc.DSAValue.{ DSAMap, DSAVal }
import models.api.typed.InitState

/**
 * Akka-based implementation of DSANodeAsync.
 */
case class DSANodeAsyncAkkaImpl(ref: NodeRef)(implicit timeout: Timeout,
                                              scheduler: Scheduler, ec: ExecutionContext)
  extends DSANodeAsyncAkkaBase(ref) with DSANodeAsync {

  def name: Future[String] = nameImpl
  def parent: Future[Option[DSANodeAsync]] = parentImpl.map(_.map(createNode))

  def displayName: Future[Option[String]] = displayNameImpl
  def displayName_=(s: Option[String]): Unit = { displayNameImpl = s }

  def value: Future[DSAVal] = valueImpl
  def value_=(v: DSAVal): Unit = { valueImpl = v }

  def attributes: Future[DSAMap] = attributesImpl
  def attributes_=(attrs: DSAMap): Unit = { attributesImpl = attrs }
  def addAttributes(cfg: (String, DSAVal)*): Unit = addAttributesImpl(cfg: _*)
  def removeAttribute(name: String): Unit = removeAttributeImpl(name)
  def clearAttributes(): Unit = clearAttributesImpl

  def children: Future[Map[String, DSANodeAsync]] =
    childrenImpl.map(_.mapValues(createNode))

  def addChild(name: String, state: InitState): Future[DSANodeAsync] =
    addChildImpl(name, state).map(createNode)

  def removeChild(name: String): Unit = removeChildImpl(name)

  def removeChildren(): Unit = removeChildrenImpl

  private def createNode(ref: NodeRef) = DSANodeAsyncAkkaImpl(ref)
}