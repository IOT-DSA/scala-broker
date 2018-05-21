package models.akka

import java.util.Date

import akka.actor.ActorRef
import models.Origin
import models.akka.DSLinkMode.DSLinkMode
import models.akka.local.LocalDSLinkFolderActor
import models.akka.responder.SimpleResponderBehavior
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
case class SidTargetsRequesterState(sid: Int, target: String)
case class RemoveTargetByRid(rid: Int)
case class RemoveTargetBySid(sid: Int)

/**
  * Internal events to recover responder behavior state.
  */
case class ResponsesProcessed(responses: List[DSAResponse])
case class RequestsProcessed(requests: Seq[DSARequest])

/**
  * Internal events to recover [[SimpleResponderBehavior]] state.
  */
case class OriginAddedToListRegistry(targetId: Int, origin: Origin)
case class OriginAddedToSubsRegistry(targetId: Int, origin: Origin)
case class ListOriginRemoved(origin: Origin)
case class SubsOriginRemoved(origin: Origin)
case class ListResponseDelivered(rsp: DSAResponse)
case class SubsResponseDelivered(rsp: DSAResponse)

/**
  * Internal events to recover [[LocalDSLinkFolderActor]] state.
  */
case class DSLinkRegistered(name: String, mode: DSLinkMode, connected: Boolean)
case class DSLinkUnregistered(name: String)
