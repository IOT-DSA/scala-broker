package models.api.typed.async

import scala.concurrent.{ ExecutionContext, Future }

import akka.actor.Scheduler
import akka.actor.typed.ActorRef.ActorRefOps
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.util.Timeout
import models.api.typed.InitState
import models.api.typed.MgmtCommand._
import models.api.typed.NodeRef
import models.rpc.DSAValue.{ DSAMap, DSAVal, longToNumericValue }

/**
 * Base class for Akka-based DSA node implementations.
 */
abstract class DSANodeAsyncAkkaBase(ref: NodeRef)(implicit timeout: Timeout,
                                                  scheduler: Scheduler, ec: ExecutionContext) {

  protected def nameImpl: Future[String] = state.map(_.name)
  protected def parentImpl: Future[Option[NodeRef]] = state.map(_.parent)

  protected def displayNameImpl: Future[Option[String]] = state.map(_.displayName)
  protected def displayNameImpl_=(s: Option[String]): Unit = ref ! SetDisplayName(s)

  protected def valueImpl: Future[DSAVal] = state.map(_.value)
  protected def valueImpl_=(v: DSAVal): Unit = ref ! SetValue(v)

  protected def attributesImpl: Future[DSAMap] = state.map(_.attributes)
  protected def attributesImpl_=(attrs: DSAMap): Unit = ref ! SetAttributes(attrs)
  protected def addAttributesImpl(cfg: (String, DSAVal)*): Unit = cfg foreach {
    case (key, value) => ref ! PutAttribute(key, value)
  }
  protected def removeAttributeImpl(name: String): Unit = ref ! RemoveAttribute(name)
  protected def clearAttributesImpl(): Unit = ref ! ClearAttributes

  protected def childrenImpl: Future[Map[String, NodeRef]] = {
    val nodes = ref ? (GetChildren)
    nodes.map { refs =>
      refs.map { ref =>
        ref.path.name -> ref
      }.toMap
    }
  }

  protected def addChildImpl(name: String, state: InitState): Future[NodeRef] =
    ref ? (AddChild(name, state, _))

  protected def removeChildImpl(name: String): Unit = ref ! RemoveChild(name)

  protected def removeChildrenImpl(): Unit = ref ! RemoveChildren

  private def state = ref ? (GetState)
}