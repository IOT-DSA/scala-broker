package models.api

import akka.actor.{ActorRef, TypedActor}
import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue.{DSAMap, DSAVal}

import scala.concurrent.Future

abstract class DistributedDSANode(val parent: Option[DSANode]) extends DSANode
  with TypedActor.Receiver with TypedActor.PreStart with TypedActor.PostStop {

  override def name: String = ???

  override def path: String = ???

  override def value: Future[DSAVal] = ???

  override def value_=(v: DSAVal): Unit = ???

  override def valueType: Future[DSAValueType] = ???

  override def valueType_=(vt: DSAValueType): Unit = ???

  override def displayName: Future[String] = ???

  override def displayName_=(name: String): Unit = ???

  override def profile: String = ???

  override def profile_=(p: String): Unit = ???

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

  override def onReceive(message: Any, sender: ActorRef): Unit = ???

  override def preStart(): Unit = ???

  override def postStop(): Unit = ???

}
