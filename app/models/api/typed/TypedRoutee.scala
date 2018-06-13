package models.api.typed

import akka.{ actor => untyped }
import akka.actor.Actor
import akka.actor.typed.ActorRef
import akka.util._
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.pattern.{ask => query}
import untyped._

trait TypedRoutee[T] extends Serializable {
  def send(message: T): Unit
  def !(msg: T): Unit = send(msg)
}

final case class TypedRefRoutee[T](ref: ActorRef[T]) extends TypedRoutee[T] {
  def send(message: T) = ref ! message
  def ask[R](factory: ActorRef[R] => T)(implicit timeout: Timeout, scheduler: untyped.Scheduler) = ref ? factory
}

//final case class ActorRefRoutee[T](ref: untyped.ActorRef) extends TypedRoutee[T] {
//  def send(message: T) = ref ! message
//}

final case class SelectionRoutee[T](selection: untyped.ActorSelection) extends TypedRoutee[T] {
  def send(message: T) = selection ! message
  def ask[R](factory: ActorRef[R] => T)(implicit timeout: Timeout, scheduler: untyped.Scheduler) = {
//    askUntyped(ref, a.untyped, timeout, f)
  }
//    selection.ask("")
}

//final case class ShardedRoutee[T](region: untyped.ActorRef, entityId: String) extends TypedRoutee[T] {
//  def send(message: T) = region.tell(wrap(message), Actor.noSender)
//  private def wrap(msg: Any) = EntityEnvelope(entityId, msg)
//}