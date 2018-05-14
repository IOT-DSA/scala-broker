package models.akka

import akka.actor.{Actor, ActorLogging, Props}
import akka.stream.SourceRef
import models.akka.Messages._
import models.akka.QoSState._
import models.rpc.DSAMessage

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.util.Random

/**
  * actor for storing dslink - specific state as
  * subscriptions, qos queue etc
  *
  * @param maxCapacity max queue size per sid
  * @param reconnectionTime timeout for storage drop
  */
class QoSState(val maxCapacity: Int = 30, val reconnectionTime:Int = 30) extends Actor with ActorLogging {

  var subscriptionsQueue = Map[Int, Queue[SubscriptionNotificationMessage]]()
  var connected = false

  var iter = subscriptionsQueue.iterator

  val rnd = new Random()

  implicit val ctx = context.system.dispatcher

  override def receive: Receive = {
    case PutNotification(message) =>
      log.debug("put {}", message)
      sender ! putMessage(message)
    case GetAndRemoveNext => getAndRemoveNext
    case Disconnected =>
      onDisconnect
      log.debug("stateKeeper {} disconnected", self.path)
    case Connected =>
      onConnected
      log.debug("stateKeeper {} connected", self.path)
    case GetAllMessages =>
      sender ! subscriptionsQueue
  }

  def onConnected() = {
    log.info("SubscriptionsStateKeeper {} connected.", self.path)
    connected = true
  }

  def killMyself() = {
    if(!connected) {
      subscriptionsQueue =  Map[Int, Queue[SubscriptionNotificationMessage]]()
      log.info("SubscriptionsStateKeeper state has been cleared {}", self.path)
    }
  }

  def onDisconnect = {
    log.info("SubscriptionsStateKeeper {} disconnected. Will try to clear state in {}", self.path, reconnectionTime seconds)
    connected = false
    subscriptionsQueue = subscriptionsQueue.filter{ case(key, value) =>
      value.nonEmpty || value.head.qos >= QoS.Durable
    }
    context.system.scheduler.scheduleOnce(reconnectionTime seconds, self, KillStateIfNotConnected)
  }

  def putMessage(message:SubscriptionNotificationMessage) = message match {
    case item @ SubscriptionNotificationMessage(_, _, _, _, QoS.Default) =>
      subscriptionsQueue = subscriptionsQueue + (item.sid -> Queue(message))
      log.debug("QoS == 0. Replacing with new queue")
      item.sid
    case item @ SubscriptionNotificationMessage(_, _, _, _, _) =>
      val queue = subscriptionsQueue.get(item.sid) map { q =>
        val newQ = if (shouldDislodge(item.sid)) q.dequeue._2 else q
        newQ enqueue message
      } getOrElse Queue(message)

      subscriptionsQueue = subscriptionsQueue + (item.sid -> queue)
      log.debug("QoS > 0. Adding new value to queue:{}", message)
      item.sid
  }

  def getAndRemoveNext() = {

    val nextSid = subscriptionsQueue.keySet.toVector(rnd.nextInt(subscriptionsQueue.keySet.size))

    var next:Option[Queue[SubscriptionNotificationMessage]] = subscriptionsQueue.get(nextSid)

    next foreach { queue =>
      subscriptionsQueue = subscriptionsQueue - nextSid
    }
    log.debug("send and remove {}", next)
    sender ! next
  }

  // in case of data overflow
  def shouldDislodge(key: Int) = queueSize(key) >= maxCapacity

  def queueSize(key: Int) = subscriptionsQueue.get(key).map(_.size).getOrElse(0)

}

object QoSState {
  def props(maxCapacity:Int = 30, reconnectionTime:Int = 30) = Props(new QoSState(maxCapacity, reconnectionTime))



  /**
    * Sent to StateKeeper to get subscription stream ref
    */
  case class GetSubscriptionSource()


  /**
    * Sent from StateKeeper with subscription stream ref
    */
  case class SubscriptionSourceMessage(sourceRef: SourceRef[DSAMessage])

  /**
    * Sent to StateKeeper to add subscription message
    * @param message
    */
  case class PutNotification(message:SubscriptionNotificationMessage)

  /**
    * Sent to StateKeeper to fetch and remove message queue by sid
    */
  case class GetAndRemoveNext()

  /**
    * Sent to StateKeeper (dslink disconnected)
    */
  case class Disconnected()

  /**
    * Sent to StateKeeper (dslink connected)
    */
  case class Connected()

  /**
    * Sent to StateKeeper to clean state if it wasn't reconnected
    */
  case class KillStateIfNotConnected()

  /**
    * fetch all messages from stateKeeper subscriptions storage
    */
  case class GetAllMessages()

}

