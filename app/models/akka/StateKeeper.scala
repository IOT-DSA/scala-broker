package models.akka

import akka.actor.{ActorLogging, Props}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import models.akka.Messages._
import models.persist.events._
import models.rpc.{DSAResponse, SubscriptionNotificationMessage}

import scala.collection.mutable
import scala.concurrent.duration._

/**
  * actor for storing dslink - specific state as
  * subscriptions, qos queue etc
  *
  * @param maxCapacity max queue size per sid
  * @param reconnectionTime timeout for storage drop
  */
class StateKeeper(val maxCapacity: Int = 30, val reconnectionTime:Int = 30)
  extends PersistentActor with ActorLogging {

  override def persistenceId: String = s"StateKeeper[${self.path}]"

  var subscriptionsQueue = Map[Int, mutable.Queue[SubscriptionNotificationMessage]]()
  var connected = false

  var iter = subscriptionsQueue.iterator
  val snapShotInterval = 1000

  def shouldUpdateSnapshot(action: ()=>Unit) = if(lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0) action()

  implicit val ctx = scala.concurrent.ExecutionContext.global

  override def receiveCommand: Receive = {
    case PutNotification(message) =>
      sender ! putMessage(message)
    case GetAndRemoveNext => getAndRemoveNext()
    case Disconnected =>
      onDisconnect()
      context.system.scheduler.scheduleOnce(reconnectionTime seconds, self, KillStateIfNotConnected)
    case Connected =>
      onConnected()
    case GetAllMessages =>
      sender ! subscriptionsQueue
      subscriptionsQueue = Map[Int, mutable.Queue[SubscriptionNotificationMessage]]()
  }

  def onConnected(){
    persist(ConnectionEvent()){ _ =>
      markAsConnected()
    }
  }

  def killMyself(){
    if(!connected) {
      persist(ClearStateEvent()){ _ =>
        clearState()
      }
    }
  }

  def clearState(){
    subscriptionsQueue =  Map[Int, mutable.Queue[SubscriptionNotificationMessage]]()
    log.info(s"SubscriptionsStateKeeper state has been cleared ${self.path}")
  }

  def onDisconnect(){
    persist(DisconnectionEvent()){_ =>
      markAsDisconnected()
      shouldUpdateSnapshot{() => saveSnapshot(QoSState(snapshotQoSFilter(_ == QoS.DurableAndPersist)))}
    }
  }

  def markAsConnected() {
    log.info(s"SubscriptionsStateKeeper ${self.path} connected.")
    connected = true
  }

  def markAsDisconnected() {
    log.info(s"SubscriptionsStateKeeper ${self.path} disconnected. Will try to clear state in $reconnectionTime seconds")
    connected = false
    subscriptionsQueue = subscriptionsQueue.filter{ case(_, value) =>
      value.nonEmpty && value.head.qos >= QoS.Durable
    }
  }

  def snapshotQoSFilter(filter:Int => Boolean): Map[Int, mutable.Queue[SubscriptionNotificationMessage]] = subscriptionsQueue
    .filter{ case (_,v) => v.nonEmpty && filter(v.head.qos)
  }

  def putMessage(message:SubscriptionNotificationMessage): Int = message match {
    case item @ SubscriptionNotificationMessage(_, _, _, _, QoS.Default) =>
      log.debug(s"put $message")
      subscriptionsQueue = subscriptionsQueue + (item.sid -> mutable.Queue(item))
      log.debug("QoS == 0. Replacing with new queue")
      item.sid
    case item @ SubscriptionNotificationMessage(_, _, _, _, _) =>
      if(item.qos == QoS.DurableAndPersist){
        persist(AddToSidEvent(item.msg, item.ack, item.responses, item.sid, item.qos)){ event =>
          log.debug("QoS == 3. Adding new value to queue")
          addToSidQueue(toMessage(event))
          shouldUpdateSnapshot{() => saveSnapshot(QoSState(snapshotQoSFilter(_ == QoS.DurableAndPersist)))}
        }
      } else {
        log.debug("QoS > 0 and < 3. Adding new value to queue")
        addToSidQueue(item)
      }
      item.sid
  }

  private def addToSidQueue(item: SubscriptionNotificationMessage): Unit = {
    log.debug(s"put $item")
    val queue = subscriptionsQueue.get(item.sid) map { q =>
      if (shouldDislodge(item.sid)) q.dequeue()
      q += item
    } getOrElse mutable.Queue(item)

    subscriptionsQueue = subscriptionsQueue + (item.sid -> queue)
  }

  def getAndRemoveNext(){

    val next: Option[(Int, mutable.Queue[SubscriptionNotificationMessage])] =

      if (iter.hasNext) {
        Some(iter.next())
      } else {
        iter = subscriptionsQueue.iterator
        if (iter.hasNext) Some(iter.next())
        else None
      }

    next foreach { case (sid, queue) =>
      val qos = queue.headOption.map(_.qos).getOrElse(QoS.Default)

      if(qos == QoS.DurableAndPersist) {
        persist(RemoveSidEvent(sid)){ event =>
          removeSid(event.sid)
          shouldUpdateSnapshot{() => saveSnapshot(QoSState(snapshotQoSFilter(_ == QoS.DurableAndPersist)))}
        }
      } else {
        subscriptionsQueue = subscriptionsQueue - sid
      }

    }
    log.debug(s"send and remove $next")
    sender ! next.map(_._2)
  }

  def removeSid(sid:Int): Unit = {
    log.debug(s"remove sid: $sid")
    subscriptionsQueue = subscriptionsQueue - sid
  }

  // in case of data overflow
  def shouldDislodge(key: Int): Boolean = queueSize(key) >= maxCapacity

  def queueSize(key: Int): Int = subscriptionsQueue.get(key).map(_.size).getOrElse(0)

  override def receiveRecover: Receive = {
   // case a:Any => log.debug(s"!!!!!!!!!!!!!!!!!!!! $a")
    case SnapshotOffer(_, snapshot:QoSState) =>
      log.debug(s"restore: SnapshotOffer($snapshot)")
      subscriptionsQueue = snapshot.state
    case event : AddToSidEvent =>
      log.debug(s"restore: $event")
      addToSidQueue(toMessage(event))
    case ConnectionEvent =>
      log.debug(s"restore: ConnectionEvent")
      markAsConnected()
    case DisconnectionEvent =>
      log.debug(s"restore: DisconnectionEvent")
      markAsDisconnected()
    case RemoveSidEvent(sid) =>
      log.debug(s"restore: RemoveSidEvent($sid)")
      removeSid(sid)
    case ClearStateEvent =>
      log.debug("restore: ClearStateEvent")
      clearState()

  }

  def toMessage(event:AddToSidEvent):SubscriptionNotificationMessage = {
    SubscriptionNotificationMessage(event.msg, event.ack, event.responses, event.sid, event.qos)
  }

}

object StateKeeper {
  def props(maxCapacity:Int = 30, reconnectionTime:Int = 30) = Props(new StateKeeper(maxCapacity, reconnectionTime))
}
