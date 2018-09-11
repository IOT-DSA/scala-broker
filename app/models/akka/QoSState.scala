package models.akka

import akka.actor.ActorRef
import akka.stream.SinkRef
import models.akka.Messages._
import models.rpc.ResponseMessage



object QoSState {

  /**
    * Sent from StateKeeper with subscription actor ref
    */
  case class SubscriptionSourceMessage(actor: SinkRef[ResponseMessage])

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

