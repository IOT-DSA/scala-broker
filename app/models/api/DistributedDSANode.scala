package models.api

import akka.actor.{ActorRef, ActorSystem, TypedActor, TypedProps}
import akka.cluster.Cluster
import akka.cluster.ddata.LWWRegister
import akka.cluster.ddata.Replicator.Get
import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue.{DSAMap, DSAVal}
import akka.cluster.ddata.Replicator._
import akka.pattern.{PromiseRef, ask}
import akka.util.Timeout
import models.Settings
import models.api.DistributedNodesRegistry.GetNode

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

class DistributedDSANode(
                          val path:String,
                          val name:String,
                          val parent: Option[DSANode],
                          val initialVal: DSAVal,
                          val valType:DSAValueType,
                          val registry:ActorRef,
                          val replicator:ActorRef
                        )(implicit cluster:Cluster, system:ActorSystem, executionContext:ExecutionContext) extends DSANode
  with TypedActor.Receiver with TypedActor.PreStart with TypedActor.PostStop {

  val dataKey = DistributedDSANodeKey(path)

  val timeout = 3 second

  implicit val implicitTimeout = Timeout(timeout)

  replicator ! Update(dataKey, empty, writeLocal)(old => old)

  override def value: Future[DSAVal] = fetch.map(_.value.value)

  override def value_=(v: DSAVal): Unit = editProperty {old =>
      old.copy(value = old.value.withValue(v))
  }

  override def valueType: Future[DSAValueType] = fetch.map(state => DSAValueType.byName(state.valueType.value))

  override def valueType_=(vt: DSAValueType): Unit = editProperty {old =>
      old.copy(valueType = old.valueType.withValue(vt.toString))}


  override def displayName: Future[String] = fetch.map(_.displayName.value)

  override def displayName_=(name: String): Unit = editProperty {old =>
    old.copy(displayName = old.displayName.withValue(name))
  }

  override def profile: String = Await.result(fetch.map(_.profile.value), timeout)

  override def profile_=(p: String): Unit = editProperty {old =>
    old.copy(profile = old.profile.withValue(p))
  }

  override def configs: Future[Map[String, DSAVal]] = ???

  override def config(name: String): Future[Option[DSAVal]] = ???

  override def addConfigs(cfg: (String, DSAVal)*): Unit = ???

  override def removeConfig(name: String): Unit = ???

  override def attributes: Future[Map[String, DSAVal]] = ???

  override def attribute(name: String): Future[Option[DSAVal]] = ???

  override def addAttributes(cfg: (String, DSAVal)*): Unit = ???

  override def removeAttribute(name: String): Unit = ???

  override def children: Future[Map[String, DSANode]] = ???

  override def child(name: String): Future[Option[DSANode]] = ???

  override def addChild(name: String): Future[DSANode] = ???

  override def removeChild(name: String): Unit = ???

  override def action: Option[DSAAction] = ???

  override def action_=(a: DSAAction): Unit = ???

  override def invoke(params: DSAMap): Unit = ???

  override def subscribe(sid: Int, ref: ActorRef): Unit = ???

  override def unsubscribe(sid: Int): Unit = ???

  override def list(rid: Int, ref: ActorRef): Unit = ???

  override def unlist(rid: Int): Unit = ???

  override def onReceive(message: Any, sender: ActorRef): Unit = {
    message match {
      case g @ GetSuccess(dataKey, future:Some[PromiseRef[Any]]) =>
        future.foreach(_.promise.success(g.get(dataKey)))
      case NotFound(dataKey, req) =>
        //TODO do logging
    }
  }

  override def preStart(): Unit = {

  }

  override def postStop(): Unit = {

  }

  private[this] def fetch = {
    val promise = PromiseRef(5 seconds)
    replicator.!(Get(dataKey, readLocal, Some(promise)))(TypedActor.context.self)
    promise.future.mapTo[DistributedDSANodeState]
  }

  private[this] def editProperty(transform: DistributedDSANodeState => DistributedDSANodeState) =
    replicator ! Update(dataKey, empty, writeLocal)(transform)

  private[this] def empty = new DistributedDSANodeState(
    LWWRegister(name),
    LWWRegister(name),
    LWWRegister(parent.map(_.path)),
    LWWRegister(path),
    LWWRegister(initialVal),
    LWWRegister(valType.toString),
    LWWRegister("")
  )
}

/**
  * Factory for [[DSANodeImpl]] instances.
  */
object DistributedDSANode {
  /**
    * Creates a new [[DSANodeImpl]] props instance.
    */
  def props(path:String, name:String, parent: Option[DSANode], initialVal:DSAVal, valType:DSAValueType, registry:ActorRef, replicator:ActorRef)
           (implicit cluster:Cluster, system:ActorSystem, executionContext:ExecutionContext) =
    TypedProps(classOf[DSANode], new DistributedDSANode(path, name, parent, initialVal, valType, registry, replicator))
}
