package models.akka

import _root_.akka.event.LoggingAdapter

trait PartOfPersistenceBehavior {
  def persist[A](event: A)(handler: A => Unit): Unit
  def onPersist: Unit
  def log: LoggingAdapter
}
