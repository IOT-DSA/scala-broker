package models.akka

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import models.Settings
import models.akka.Messages._
import models.akka.QoSState._
import models.metrics.Meter

import scala.collection.mutable.Queue
import scala.collection.mutable
import scala.concurrent.duration._

/**
  * actor for storing dslink - specific state as
  * subscriptions, qos queue etc
  *
  * @param maxCapacity max queue size per sid
  * @param reconnectionTime timeout for storage drop
  */
class QoSState(val maxCapacity: Int = 30, val reconnectionTime:Int = 30) extends Actor with ActorLogging with Meter {

  val subscriptionsQueue = mutable.HashMap[Int, Queue[SubscriptionNotificationMessage]]()
  var connected = false
  val maxBatch = Settings.Subscriptions.maxBatchSize

  var iter = subscriptionsQueue.iterator

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
      sender ! Map[Int, Queue[SubscriptionNotificationMessage]](subscriptionsQueue.toSeq:_*)
      subscriptionsQueue.clear()
  }

  def onConnected() = {
    log.info("SubscriptionsStateKeeper {} connected.", self.path)
    connected = true
  }

  def killMyself() = {
    if(!connected) {
      subscriptionsQueue.clear()
      meterTags("qos.state.clear")
      log.info("SubscriptionsStateKeeper state has been cleared {}", self.path)
    }
  }

  def onDisconnect = {
    log.info("SubscriptionsStateKeeper {} disconnected. Will try to clear state in {}", self.path, reconnectionTime seconds)
    connected = false
    subscriptionsQueue.filter{ case(key, value) =>
      value.nonEmpty || value.head.qos >= QoS.Durable
    }.map(_._1).foreach(subscriptionsQueue.remove)
    context.system.scheduler.scheduleOnce(reconnectionTime seconds, self, KillStateIfNotConnected)
  }

  def putMessage(message:SubscriptionNotificationMessage) = {
    meterTags("qos.in.level."+message.qos.index.toString)
    val result = message match {
      case item @ SubscriptionNotificationMessage( _, _, QoS.Default) =>
        subscriptionsQueue += (item.sid -> Queue(message))
        log.debug("QoS == 0. Replacing with new queue")
        histogramValue(s"qos.level.0.queue.size")(1)
        item.sid
      case item @ SubscriptionNotificationMessage( _, _, _) =>
        val maybeQ = subscriptionsQueue.get(item.sid).orElse(Some(Queue[SubscriptionNotificationMessage]()))

        if(maybeQ.isEmpty){
          subscriptionsQueue.put(item.sid, maybeQ.get)
        }

        maybeQ.foreach{ q =>
          q.enqueue(message)
          histogramValue(s"qos.level.${item.qos.index}.queue.size")(q.size)
          log.debug("QoS > 0. Adding new value to queue:{}", message)
        }
        item.sid
      case other => log.error(s"Unexpected message type: ${other.getClass}")
    }

    histogramValue("qos.sids.size")(subscriptionsQueue.size)

    result
  }

  def getAndRemoveNext() = {

    def next:Option[(Int, Queue[SubscriptionNotificationMessage])] =
      if(iter.hasNext){
        Some(iter.next())
      } else {
        iter = subscriptionsQueue.iterator
        if(iter.hasNext) Some(iter.next())
        else None
      }

    val toSend = mutable.ArrayBuffer[SubscriptionNotificationMessage]()

    while(!subscriptionsQueue.isEmpty && toSend.size < maxBatch){
      next.foreach{
        case (key, messages) =>
          if(!messages.isEmpty){
            toSend += messages.dequeue()
          }
          if(messages.isEmpty) subscriptionsQueue -= key
      }
    }

    histogramValue("qos.sids.size")(subscriptionsQueue.size)
    histogramValue("qos.send.batch")(1)
    histogramValue("qos.send.messages")(toSend.size)

    log.debug("send and remove {}", toSend)
    sender ! toSend
  }

  // in case of data overflow
  def shouldDislodge(key: Int) = queueSize(key) >= maxCapacity

  def queueSize(key: Int) = subscriptionsQueue.get(key).map(_.size).getOrElse(0)

}

object QoSState {
  def props(maxCapacity:Int = 30, reconnectionTime:Int = 30) = Props(new QoSState(maxCapacity, reconnectionTime))



  /**
    * Sent from StateKeeper with subscription actor ref
    */
  case class SubscriptionSourceMessage(actor: ActorRef)

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

