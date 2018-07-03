package models.akka

import java.util.Date

import akka.actor.ActorRef
import models.akka.DSLinkMode.DSLinkMode
import models.akka.Messages.LinkState
import models.akka.local.LocalDSLinkFolderActor
import models.rpc.{DSARequest, DSAResponse}

import collection.mutable.Map

/**
  * Internal states of DSLink Actor layers to be persist.
  */
case class DSLinkState(baseState: DSLinkBaseState,
                       requesterBehaviorState: RequesterBehaviorState,
                       folderState: DSLinkFolderState)

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
case class RemoveTargetBySid(sids: Int*)
case class LastRidSet(rid: Int)
case class RequesterBehaviorState(targetsByRid: Map[Int, String], targetsBySid: Map[Int, PathAndQos], lastRid: Int)

/**
  * Internal events to recover responder behavior state.
  */
case class ResponsesProcessed(responses: List[DSAResponse])
case class RequestsProcessed(requests: Seq[DSARequest])

/**
  * Internal events to recover [[DSLinkFolderActor]] and [[LocalDSLinkFolderActor]] state.
  */
case class DSLinkFolderState(links: collection.immutable.Map[String, LinkState], listRid: Option[Int])
case class DSLinkCreated(name: String)
case class DSLinkRemoved(names: String*)
case class DSLinkRegistered(name: String, mode: DSLinkMode, connected: Boolean)
case class DSLinkUnregistered(name: String)
case class ListRidUpdated(listRid: Option[Int])
