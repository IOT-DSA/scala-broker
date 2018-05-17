package models.akka

import akka.actor.{Actor, ActorLogging, Props}
import akka.stream.SourceRef
import models.akka.Messages._
import models.akka.QoSState._
import models.metrics.Meter
import models.rpc.DSAMessage

import scala.collection.immutable.Queue
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
      case item @ SubscriptionNotificationMessage(_, _, _, _, QoS.Default) =>
        subscriptionsQueue += (item.sid -> Queue(message))
        log.debug("QoS == 0. Replacing with new queue")
        histogramValue(s"qos.level.0.queue.size")(1)
        item.sid
      case item @ SubscriptionNotificationMessage(_, _, _, _, _) =>
        val queue = subscriptionsQueue.get(item.sid) map { q =>
          val newQ = if (shouldDislodge(item.sid)) {
            countTags("qos.dislodge")
            q.dequeue._2
          } else q
          newQ enqueue message
        } getOrElse {
          Queue(message)
        }

        histogramValue(s"qos.level.${item.qos.index}.queue.size")(queue.size)
        subscriptionsQueue += (item.sid -> queue)
        log.debug("QoS > 0. Adding new value to queue:{}", message)
        item.sid
    }

    histogramValue("qos.sids.size")(subscriptionsQueue.size)

    result
  }

  def getAndRemoveNext() = {

    var next:Option[(Int, Queue[SubscriptionNotificationMessage])] =
      if(iter.hasNext){
        Some(iter.next())
      } else {
        iter = subscriptionsQueue.iterator
        if(iter.hasNext) Some(iter.next())
        else None
      }

    next foreach { case (sid, queue) =>
      subscriptionsQueue -= sid
      histogramValue("qos.sids.size")(subscriptionsQueue.size)
    }
    log.debug("send and remove {}", next)
    sender ! next.map(_._2)
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

