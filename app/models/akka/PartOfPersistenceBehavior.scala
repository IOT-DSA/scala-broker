package models.akka

import _root_.akka.event.LoggingAdapter

trait PartOfPersistenceBehavior {
  val ownId: String
  def persist[A](event: A)(handler: A => Unit): Unit
  def onPersist: Unit
  def log: LoggingAdapter
}
