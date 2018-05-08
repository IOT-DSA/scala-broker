package models.akka

import akka.actor.{Actor, ActorLogging, Props}
import models.akka.Messages._
import models.rpc.SubscriptionNotificationMessage

import scala.collection.mutable
import scala.concurrent.duration._

/**
  * actor for storing dslink - specific state as
  * subscriptions, qos queue etc
  *
  * @param maxCapacity max queue size per sid
  * @param reconnectionTime timeout for storage drop
  */
class StateKeeper(val maxCapacity: Int = 30, val reconnectionTime:Int = 30) extends Actor with ActorLogging {

  var subscriptionsQueue = Map[Int, mutable.Queue[SubscriptionNotificationMessage]]()
  var connected = false

  var iter = subscriptionsQueue.iterator

  implicit val ctx = scala.concurrent.ExecutionContext.global

  override def receive: Receive = {
    case PutNotification(message) =>
      log.debug(s"put $message")
      sender ! putMessage(message)
    case GetAndRemoveNext => getAndRemoveNext
    case Disconnected =>
      onDisconnect
      log.debug(s"stateKeeper ${self.path} disconnected")
    case Connected =>
      onConnected
      log.debug(s"stateKeeper ${self.path} connected")
    case GetAllMessages =>
      sender ! subscriptionsQueue
  }

  def onConnected = {
    log.info(s"SubscriptionsStateKeeper ${self.path} connected.")
    connected = true
  }

  def killMyself = {
    if(!connected) {
      subscriptionsQueue =  Map[Int, mutable.Queue[SubscriptionNotificationMessage]]()
      log.info(s"SubscriptionsStateKeeper state has been cleared ${self.path}")
    }
  }

  def onDisconnect = {
    log.info(s"SubscriptionsStateKeeper ${self.path} disconnected. Will try to clear state in ${reconnectionTime} seconds")
    connected = false
    subscriptionsQueue = subscriptionsQueue.filter{ case(key, value) => {
      if(value.isEmpty) false
      if(value.head.qos < QoS.Durable) false
      true
    }}
    context.system.scheduler.scheduleOnce(reconnectionTime seconds, self, KillStateIfNotConnected)
  }

  def putMessage(message:SubscriptionNotificationMessage) = message match {
    case item @ SubscriptionNotificationMessage(_, _, _, _, QoS.Default) => {
      subscriptionsQueue = subscriptionsQueue + (item.sid -> mutable.Queue(message))
      log.debug("QoS == 0. Replacing with new queue")
      item.sid
    }
    case item @ SubscriptionNotificationMessage(_, _, _, _, _) => {
      val queue = subscriptionsQueue.get(item.sid) map { q =>
        if (shouldDislodge(item.sid)) q.dequeue()
        q += message
      } getOrElse (mutable.Queue(message))

      subscriptionsQueue = subscriptionsQueue + (item.sid -> queue)
      log.debug("QoS > 0. Adding new value to queue")
      item.sid
    }
  }

  def getAndRemoveNext = {

    var next:Option[(Int, mutable.Queue[SubscriptionNotificationMessage])] =

    if(iter.hasNext){
      Some(iter.next())
    } else {
      iter = subscriptionsQueue.iterator
      if(iter.hasNext) Some(iter.next())
      else None
    }

    next foreach { case (sid, queue) =>
      subscriptionsQueue = subscriptionsQueue - sid
    }
    log.debug(s"send and remove $next")
    next.foreach{case (sid, queue) =>
      val ids = queue.flatMap(_.responses.map(_.rid))
    }
    sender ! next.map(_._2)
  }

  // in case of data overflow
  def shouldDislodge(key: Int) = queueSize(key) >= maxCapacity

  def queueSize(key: Int) = subscriptionsQueue.get(key).map(_.size).getOrElse(0)

}

object StateKeeper {
  def props(maxCapacity:Int = 30, reconnectionTime:Int = 30) = Props(new StateKeeper(maxCapacity, reconnectionTime))
}
