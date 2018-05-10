package models.persist.events

import models.akka.QoS
import models.rpc.{DSAResponse, SubscriptionNotificationMessage}

import scala.collection.mutable


sealed trait StateKeeperEvent

//object AddToSidEvent{
//  def apply(message:SubscriptionNotificationMessage):AddToSidEvent = {
//    AddToSidEvent(message.msg, message.ack, message.responses, message.sid, message.qos.index)
//  }
//}

case class AddToSidEvent(msg:Int, ack:Option[Int], responses:List[DSAResponse], sid:Int, qos:Int) extends StateKeeperEvent

case class ConnectionEvent() extends StateKeeperEvent
case class DisconnectionEvent() extends StateKeeperEvent
case class RemoveSidEvent(sid: Int) extends StateKeeperEvent
case class ClearStateEvent() extends StateKeeperEvent
case class QoSState(state: Map[Int, mutable.Queue[SubscriptionNotificationMessage]])

