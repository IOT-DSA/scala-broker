package models.api

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.Duration

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

import DSAValueType.{ DSADynamic, DSAValueType }
import akka.actor.{ ActorRef, TypedActor, TypedProps }
import akka.event.Logging
import models.{ RequestEnvelope, ResponseEnvelope, Settings }
import models.rpc._
import models.rpc.DSAValue.{ DSAMap, DSAVal, StringValue, array, longToNumericValue, obj }

/**
 * A structural unit in Node API.
 */
trait DSANode {
  def parent: Option[DSANode]
  def name: String
  def path: String

  def value: Future[DSAVal]
  def value_=(v: DSAVal): Unit

  def valueType: Future[DSAValueType]
  def valueType_=(vt: DSAValueType): Unit

  def displayName: Future[String]
  def displayName_=(name: String): Unit

  def profile: String
  def profile_=(p: String): Unit

  def configs: Future[Map[String, DSAVal]]
  def config(name: String): Future[Option[DSAVal]]
  def addConfigs(cfg: (String, DSAVal)*): Unit
  def removeConfig(name: String): Unit

  def attributes: Future[Map[String, DSAVal]]
  def attribute(name: String): Future[Option[DSAVal]]
  def addAttributes(cfg: (String, DSAVal)*): Unit
  def removeAttribute(name: String): Unit

  def children: Future[Map[String, DSANode]]
  def child(name: String): Future[Option[DSANode]]
  def addChild(name: String): Future[DSANode]
  def removeChild(name: String): Unit

  def action: Option[DSAAction]
  def action_=(a: DSAAction): Unit

  def invoke(params: DSAMap): Unit

  def subscribe(sid: Int, ref: ActorRef): Unit
  def unsubscribe(sid: Int): Unit

  def list(rid: Int, ref: ActorRef): Unit
  def unlist(rid: Int): Unit
}

/**
  * Factory for [[DSANodeImpl]] instances.
  */
object DSANode {
  /**
    * Creates a new [[DSANodeImpl]] props instance.
    */
  def props(parent: Option[DSANode]) = TypedProps(classOf[DSANode], new DSANodeImpl(parent))
}
