package models.akka

import java.util.Date

import akka.actor.ActorRef
import models.akka.DSLinkMode.DSLinkMode
import models.akka.local.LocalDSLinkFolderActor
import models.rpc.{DSARequest, DSAResponse}

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
case class SidTargetsRequesterState(sid: Int, pathAndQos: PathAndQos)
case class RemoveTargetByRid(rid: Int)
case class RemoveTargetBySid(sid: Int)
case class LastRidSet(rid: Int)

/**
  * Internal events to recover responder behavior state.
  */
case class ResponsesProcessed(responses: List[DSAResponse])
case class RequestsProcessed(requests: Seq[DSARequest])

/**
  * Internal events to recover [[LocalDSLinkFolderActor]] state.
  */
case class DSLinkCreated(name: String)
case class DSLinkRemoved(name: String)
case class DSLinkRegistered(name: String, mode: DSLinkMode, connected: Boolean)
case class DSLinkUnregistered(name: String)

/**
  * Internal events to recover [[DSLinkFolderActor]] state.
  */
case class LinkAdded()
case class LinkRemoved()
