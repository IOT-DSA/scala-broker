package models.util

import akka.event.LoggingAdapter
import models.akka.PartOfPersistenceBehavior

class PartOfPersistenceBehaviorStub extends PartOfPersistenceBehavior {
  override val ownId: String = ""
  override def persist[A](event: A)(handler: A => Unit): Unit = handler(event)
  override def onPersist: Unit = {}
  override def log: LoggingAdapter = new LoggingAdapter {
    override protected def notifyError(message: String): Unit = ???
    override protected def notifyError(cause: Throwable, message: String): Unit = ???
    override protected def notifyDebug(message: String): Unit = ???
    override def isInfoEnabled: Boolean = ???
    override protected def notifyWarning(message: String): Unit = ???
    override def isErrorEnabled: Boolean = ???
    override protected def notifyInfo(message: String): Unit = ???
    override def isDebugEnabled: Boolean = false
    override def isWarningEnabled: Boolean = ???
  }
}
