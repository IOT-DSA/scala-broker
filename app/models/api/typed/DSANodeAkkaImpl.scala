/*package models.api.typed

import scala.concurrent.{Await, ExecutionContext, Future}
import MgmtCommand._
import akka.actor.{ActorPath, Scheduler}
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.util.Timeout
import models.rpc.DSAValue.{DSAMap, DSAVal, longToNumericValue}

/**
 * Base class for Akka-based DSA node implementations.
 */
abstract class DSANodeAsyncAkkaBase(ref: NodeRef)(implicit timeout: Timeout,
                                                  scheduler: Scheduler, ec: ExecutionContext) {

  def nameImpl: Future[String] = state.map(_.name)
  def parentImpl: Future[Option[ActorPath]] = state.map(_.parent)

  def displayNameImpl: Future[String] = state.map(_.displayName)
  def displayNameImpl_=(s: String): Unit = ref ! SetDisplayName(s)

  def valueImpl: Future[DSAVal] = state.map(_.value)
  def valueImpl_=(v: DSAVal): Unit = ref ! SetValue(v)

  def attributesImpl: Future[DSAMap] = state.map(_.attributes)
  def attributesImpl_=(attrs: DSAMap): Unit = ref ! SetAttributes(attrs)
  def addAttributesImpl(cfg: (String, DSAVal)*): Unit = cfg foreach {
    case (key, value) => ref ! PutAttribute(key, value)
  }
  def removeAttributeImpl(name: String): Unit = ref ! RemoveAttribute(name)

  def childrenImpl: Future[Map[String, NodeRef]] = {
    val nodes = ref ? (GetChildren(_))
    nodes.map { refs =>
      refs.map { ref =>
        ref.path.name -> ref
      }.toMap
    }
  }

  def addChildImpl(name: String): Future[NodeRef] = {
    val state = DSANodeState(Some(ref.path), name, null, 0, Map.empty, List.empty)
    ref ? (AddChild(state, _))
  }

  def removeChildImpl(name: String): Unit = ref ! RemoveChild(name)

  protected def state = ref ? (GetState(_))
}

/**
 * Akka-based implementation of DSANodeAsync.
 */
class DSANodeAsyncAkkaImpl(ref: NodeRef)(implicit timeout: Timeout,
                                         scheduler: Scheduler, ec: ExecutionContext)
  extends DSANodeAsyncAkkaBase(ref) with DSANodeAsync {

  def name: Future[String] = nameImpl
  def parent: Future[Option[DSANodeAsync]] = parentImpl.map(_.map(new DSANodeAsyncAkkaImpl(_)))

  def displayName: Future[String] = displayNameImpl
  def displayName_=(s: String): Unit = displayNameImpl_=(s)

  def value: Future[DSAVal] = valueImpl
  def value_=(v: DSAVal): Unit = valueImpl_=(v)

  def attributes: Future[DSAMap] = attributesImpl
  def attributes_=(attrs: DSAMap): Unit = attributesImpl_=(attrs)
  def addAttributes(cfg: (String, DSAVal)*): Unit = addAttributesImpl(cfg: _*)
  def removeAttribute(name: String): Unit = removeAttributeImpl(name)

  def children: Future[Map[String, DSANodeAsync]] =
    childrenImpl.map(_.mapValues(new DSANodeAsyncAkkaImpl(_)))

  def addChild(name: String): Future[DSANodeAsync] =
    addChildImpl(name).map(new DSANodeAsyncAkkaImpl(_))

  def removeChild(name: String): Unit = removeChildImpl(name)
}

/**
 * Akka-based implementation of DSANode.
 */
class DSANodeAkkaImpl(ref: NodeRef)(implicit timeout: Timeout,
                                    scheduler: Scheduler, ec: ExecutionContext)
  extends DSANodeAsyncAkkaBase(ref) with DSANode {

  private implicit def block[T](future: Future[T]): T = Await.result(future, timeout.duration)

  def name: String = nameImpl
  def parent: Option[DSANode] = parentImpl.map(_.map(new DSANodeAkkaImpl(_)))

  def displayName: String = displayNameImpl
  def displayName_=(s: String): Unit = displayNameImpl_=(s)

  def value: DSAVal = valueImpl
  def value_=(v: DSAVal): Unit = valueImpl_=(v)

  def attributes: DSAMap = attributesImpl
  def attributes_=(attrs: DSAMap): Unit = attributesImpl_=(attrs)
  def addAttributes(cfg: (String, DSAVal)*): Unit = addAttributesImpl(cfg: _*)
  def removeAttribute(name: String): Unit = removeAttributeImpl(name)

  def children: Map[String, DSANode] = childrenImpl.map(_.mapValues(new DSANodeAkkaImpl(_)))
  def addChild(name: String): DSANode = addChildImpl(name).map(new DSANodeAkkaImpl(_))
  def removeChild(name: String): Unit = removeChildImpl(name)
}*/