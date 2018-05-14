package models.akka

import java.util.Date

import akka.actor.ActorRef

/**
  * Internal states of DSLink Actor layers to be persist.
  */

/**
  * Internal State of the abstract layer.
  */
case class DSLinkBaseState(endpoint: Option[ActorRef],
                           connInfo: ConnectionInfo,
                           lastConnected: Option[Date] = None,
                           lastDisconnected: Option[Date] = None)

/**
  * Internal events to recover requester behavior state.
  */
case class RidTargetsRequesterState(rid: Int, target: String)
case class SidTargetsRequesterState(sid: Int, target: String)
case class RemoveTargetByRid(rid: Int)
case class RemoveTargetBySid(sid: Int)
