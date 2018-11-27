package models.sdk.node.async

import akka.Done
import akka.actor.Scheduler
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.typed.scaladsl._
import akka.util.Timeout
import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue.{DSAMap, DSAVal}
import models.sdk.NodeRef
import models.sdk.node.NodeCommand._
import models.sdk.node.NodeEvent.{AttributeEvent, ChildEvent, ConfigEvent, ValueEvent}
import models.sdk.node.{ActionResult, NodeAction, NodeCommand, NodeStatus}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Base class for Akka-based node API implementations.
  */
abstract class NodeAsyncAkkaBase(ref: NodeRef)(implicit timeout: Timeout, scheduler: Scheduler,
                                               ec: ExecutionContext, mat: ActorMaterializer) {

  protected def _status: Future[NodeStatus] = fromStatus(identity)

  protected def _value: Future[Option[DSAVal]] = fromStatus(_.value)
  protected def _value_=(v: Option[DSAVal]): Unit = ref ! SetValue(v)

  protected def _displayName: Future[Option[String]] = fromStatus(_.displayName)
  protected def _displayName_=(str: String): Unit = ref ! SetDisplayName(str)

  protected def _valueType: Future[DSAValueType] = fromStatus(_.valueType)
  protected def _valueType_=(vt: DSAValueType): Unit = ref ! SetValueType(vt)

  protected def _profile: Future[String] = fromStatus(_.profile)
  protected def _profile_=(prf: String): Unit = ref ! SetProfile(prf)

  protected def _action: Future[Option[NodeAction]] = ref ? GetAction
  protected def _action_=(a: NodeAction): Unit = ref ! SetAction(a)
  protected def _invoke(args: DSAMap): Future[ActionResult] = ref ? (Invoke(args, _))

  protected def _attributes: Future[DSAMap] = fromStatus(_.attributes)
  protected def _attributes_=(attrs: DSAMap): Unit = ref ! SetAttributes(attrs)
  protected def _putAttribute(name: String, value: DSAVal): Unit = ref ! PutAttribute(name, value)
  protected def _removeAttribute(name: String): Unit = ref ! RemoveAttribute(name)
  protected def _clearAttributes(): Unit = ref ! ClearAttributes

  protected def _configs: Future[DSAMap] = fromStatus(_.configs)
  protected def _configs_=(cfgs: DSAMap): Unit = ref ! SetConfigs(cfgs)
  protected def _putConfig(name: String, value: DSAVal): Unit = ref ! PutConfig(name, value)
  protected def _removeConfig(name: String): Unit = ref ! RemoveConfig(name)
  protected def _clearConfigs(): Unit = ref ! ClearConfigs

  protected def _children: Future[Map[String, NodeRef]] = (ref ? GetChildren).map { refs =>
    refs.map(ref => ref.path.name -> ref).toMap
  }
  protected def _addChild(name: String): Future[NodeRef] = ref ? (AddChild(name, _))
  protected def _removeChild(name: String): Future[Done] = ref ? (RemoveChild(name, _))
  protected def _removeChildren(): Future[Done] = ref ? RemoveChildren

  protected lazy val _valueEvents: Source[ValueEvent, _] = createEventSource(AddValueListener)
  protected lazy val _attributeEvents: Source[AttributeEvent, _] = createEventSource(AddAttributeListener)
  protected lazy val _configEvents: Source[ConfigEvent, _] = createEventSource(AddConfigListener)
  protected lazy val _childEvents: Source[ChildEvent, _] = createEventSource(AddChildListener)

  /**
    * Queries the node status and extracts a field from it.
    *
    * @param extractor
    * @tparam T
    * @return
    */
  private def fromStatus[T](extractor: NodeStatus => T)= (ref ? GetStatus) map extractor

  /**
    * Creates an event source for the specified event type.
    *
    * @param cmdFunc
    * @tparam T
    * @return
    */
  private def createEventSource[T](cmdFunc: ActorRef[T] => NodeCommand) = {
    val preSource = ActorSource.actorRef[T](
      completionMatcher = PartialFunction.empty,
      failureMatcher = PartialFunction.empty,
      bufferSize = 0,
      overflowStrategy = OverflowStrategy.fail)
    val (listener, source) = preSource.preMaterialize()
    ref ! cmdFunc(listener)
    source
  }
}
