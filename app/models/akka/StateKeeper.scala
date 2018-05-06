package models.akka

import akka.actor.{Actor, ActorLogging, Props}
import models.akka.Messages._
import models.rpc.SubscriptionNotificationMessage

import scala.collection.mutable
import scala.collection.mutable.HashMap
import scala.concurrent.duration._

class StateKeeper(val maxCapacity: Int = 30, val reconnectionTime:Int = 30) extends Actor with ActorLogging {

  var subscriptionsQueue = new HashMap[Int, mutable.Queue[SubscriptionNotificationMessage]]
  var connected = false

  implicit val ctx = scala.concurrent.ExecutionContext.global

  override def receive: Receive = {
    case PutNotification(message) => putMessage(message)
    case GetAndRemoveNext => getAndRemoveNext
    case Disconnected => onDisconnect
    case Connected => onConnected
    case IsEmpty => sender ! subscriptionsQueue.isEmpty
  }

  def onConnected = {
    log.info(s"SubscriptionsStateKeeper ${self.path} connected.")
    connected = true
  }

  def killMyself = {
    if(!connected) {
      subscriptionsQueue = new HashMap[Int, mutable.Queue[SubscriptionNotificationMessage]]
      log.info(s"SubscriptionsStateKeeper state has been cleared ${self.path}")
    }
  }

  def onDisconnect = {
    log.info(s"SubscriptionsStateKeeper ${self.path} disconnected. Will try to clear state in ${reconnectionTime} seconds")
    connected = false
    context.system.scheduler.scheduleOnce(reconnectionTime seconds, self, KillStateIfNotConnected)
  }

  def putMessage(message:SubscriptionNotificationMessage) = message match {
    case item @ SubscriptionNotificationMessage(_, _, _, _, QoS.Default) => {
      subscriptionsQueue.put(item.sid, mutable.Queue(message))
      log.debug("QoS == 0. Replacing with new queue")
    }
    case item @ SubscriptionNotificationMessage(_, _, _, _, _) => {
      val queue = subscriptionsQueue.get(item.sid) map { q =>
        if (shouldDislodge(item.sid)) q.dequeue()
        q += message
      } getOrElse (mutable.Queue(message))

      subscriptionsQueue.put(item.sid, queue)
      log.debug("QoS > 0. Adding new value to queue")
    }
  }

  def getAndRemoveNext = {
    //side effect
    val next = subscriptionsQueue.headOption

    sender ! next

    next foreach { case (sid, queue) =>
      subscriptionsQueue.remove(sid)
    }
  }

  // in case of data overflow
  def shouldDislodge(key: Int) = queueSize(key) >= maxCapacity

  def queueSize(key: Int) = subscriptionsQueue.get(key).map(_.size).getOrElse(0)

}

object StateKeeper {
  def props(maxCapacity:Int = 30, reconnectionTime:Int = 30) = Props(new StateKeeper(maxCapacity, reconnectionTime))
}
