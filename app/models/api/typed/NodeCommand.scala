package models.api.typed

import akka.actor.typed.ActorRef
import models.RequestEnvelope
import models.rpc.DSAValue.{ DSAMap, DSAVal }

/**
 * Command that can be sent to a typed actor representing a DSA node. It combines
 * management commands that target the node state and DSA-compliant messages.
 */
sealed trait NodeCommand extends Serializable

/**
 * Commands that change the node state or affect its children.
 */
sealed trait MgmtCommand extends NodeCommand

/**
 * Available management commands.
 */
object MgmtCommand {
  type Cmd = MgmtCommand
  sealed trait CmdR[T] extends MgmtCommand with Replyable[T]
  sealed trait CmdOR[T] extends MgmtCommand with MaybeReplyable[T]

  final case class GetState(replyTo: ActorRef[DSANodeState]) extends CmdR[DSANodeState]
  final case class SetDisplayName(name: String) extends Cmd
  final case class SetValue(value: DSAVal) extends Cmd
  final case class SetAttributes(attributes: DSAMap) extends Cmd
  final case class PutAttribute(name: String, value: DSAVal) extends Cmd
  final case class RemoveAttribute(name: String) extends Cmd

  final case class PersistState(state: DSANodeState) extends Cmd

  final case class GetChildren(replyTo: ActorRef[NodeRefs]) extends CmdR[NodeRefs]
  final case class AddChild(state: DSANodeState, replyTo: ActorRef[NodeRef]) extends CmdR[NodeRef]
  final case class RemoveChild(name: String) extends Cmd

  final case object Stop extends Cmd
}

sealed trait MgmtEvent extends Serializable
final case class DisplayNameChanged(name: String) extends MgmtEvent
final case class ValueChanged(value: DSAVal) extends MgmtEvent
final case class AttributesChanged(attributes: DSAMap) extends MgmtEvent
final case class AttributeAdded(name: String, value: DSAVal) extends MgmtEvent
final case class AttributeRemoved(name: String) extends MgmtEvent
final case class StatePersisted(state: DSANodeState) extends MgmtEvent
//final case class ChildRemoved(name: String) extends MgmtEvent


/**
 * Commands that contain DSA-compliant messages that the node needs to process.
 */
sealed trait DSACommand extends NodeCommand

/**
 * Available DSA commands.
 */
object DSACommand {
  type Cmd = DSACommand
  sealed trait CmdR[T] extends DSACommand with Replyable[T]
  sealed trait CmdOR[T] extends DSACommand with MaybeReplyable[T]

  final case class ProcessRequests(env: RequestEnvelope) extends Cmd
}