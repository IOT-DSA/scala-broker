package models.akka

import java.util.Date

import akka.actor.ActorRef
import akka.routing.ConsistentHashingPool
import models.akka.DSLinkMode.DSLinkMode
import models.akka.Messages.LinkState
import models.akka.local.LocalDSLinkFolderActor
import models.akka.responder._
import models.rpc.DSAValue.DSAVal
import models.rpc.{DSARequest, DSAResponse}

import collection.mutable.{ Map => MutableMap }
import collection.immutable.{ Map => ImmutableMap }

/**
  * Internal states of DSLink Actor layers to be persist.
  */
case class DSLinkState(baseState: DSLinkBaseState,
                       requesterBehaviorState: RequesterBehaviorState,
                       folderState: DSLinkFolderState,
                       responderBehaviorState: ResponderBehaviorState)

/**
  * Internal State of the abstract layer [[AbstractDSLinkActor]].
  */
case class DSLinkBaseState(endpoint: Option[ActorRef],
                           connInfo: ConnectionInfo,
                           lastConnected: Option[Date] = None,
                           lastDisconnected: Option[Date] = None)

/**
  * Internal events to recover requester behavior [[RequesterBehaviorState]] state.
  */
case class RidTargetsRequesterState(rid: Int, target: String)
case class SidTargetsRequesterState(sid: Int, pathAndQos: PathAndQos)
case class RemoveTargetByRid(rid: Int)
case class RemoveTargetBySid(sids: Int*)
case class LastRidSet(rid: Int)
case class RequesterBehaviorState(targetsByRid: MutableMap[Int, String], targetsBySid: MutableMap[Int, PathAndQos], lastRid: Int)

/**
  * Internal events to recover responder behavior [[ResponderBehaviorState]], [[SimpleResponderBehaviorState]] and [[PooledResponderBehavior]] state.
  */
sealed trait AbstractResponderBehaviorState
case class ResponderBehaviorState(main: MainResponderBehaviorState, additional: AbstractResponderBehaviorState)
case class MainResponderBehaviorState(ridRegistry: RidRegistry, sidRegistry: SidRegistry, attributes: MutableMap[String, ImmutableMap[String, DSAVal]])
case class SimpleResponderBehaviorState(listBindings: ImmutableMap[Int, GroupCallRecord], subsBindings: ImmutableMap[Int, GroupCallRecord]) extends AbstractResponderBehaviorState
case class PooledResponderBehaviorState(listPool: ConsistentHashingPool, subsPool: ConsistentHashingPool) extends AbstractResponderBehaviorState
case class ResponsesProcessed(responses: List[DSAResponse])
case class RequestsProcessed(requests: Seq[DSARequest])

/**
  * Internal events to recover [[DSLinkFolderActor]] and [[LocalDSLinkFolderActor]] state.
  */
case class DSLinkFolderState(links: ImmutableMap[String, LinkState], listRid: Option[Int])
case class DSLinkCreated(name: String)
case class DSLinkRemoved(names: String*)
case class DSLinkRegistered(name: String, mode: DSLinkMode, connected: Boolean)
case class DSLinkUnregistered(name: String)
case class ListRidUpdated(listRid: Option[Int])
