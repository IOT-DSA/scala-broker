package models.akka

import java.util.Date

import akka.actor.ActorRef

/**
  * Internal state of DSLink Actor to be persist.
  */
case class DSLinkState(endpoint: Option[ActorRef],
                       connInfo: ConnectionInfo,
                       lastConnected: Option[Date] = None,
                       lastDisconnected: Option[Date] = None)
