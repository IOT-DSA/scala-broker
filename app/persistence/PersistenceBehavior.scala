package persistence

import akka.event.LoggingAdapter


/**
  * Using to provide persisting feature from actor to inner classes
  * @tparam A type of events superclass
  * @tparam S type of state which will be passed to screenshot method right after taking snapshot
  */
trait PersistenceBehavior[A, S] {
  def persist[E <: A](event: A, state: S)(handler: E => Unit): Unit
}

object PersistenceBehavior {
  /**
    * Usually we expect persisters to make some additional logic around persisting like logging. This method helps to do that
    * @param ownId
    * @param log
    * @param _persist
    * @param takeSnapshot
    * @tparam A
    * @tparam S
    * @return
    */
  def createPersister[A, S] (ownId: String, log: LoggingAdapter,
                             _persist: A => ( A  => Unit ) => Unit, takeSnapshot: S => Unit): PersistenceBehavior[A, S] = new PersistenceBehavior[A, S] {
    override def persist[E <: A](event: A, state: S)(handler: E => Unit): Unit = {
      _persist(event) { event =>
        log.debug("{} persisting {}", ownId, event)
        handler(event)
        takeSnapshot(state)
      }
    }
  }
}