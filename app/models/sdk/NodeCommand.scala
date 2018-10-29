package models.sdk

import akka.Done
import akka.actor.typed.ActorRef
import models.rpc.DSAValue.{DSAMap, DSAVal}

/**
  * Command that can be sent to a typed actor representing a DSA node.
  */
sealed trait NodeCommand extends Serializable

/**
  * Available node commands.
  */
object NodeCommand {
  type Cmd = NodeCommand

  final case class GetStatus(replyTo: ActorRef[NodeStatus]) extends Cmd

  final case class SetDisplayName(name: String) extends Cmd
  final case class SetValue(value: Option[DSAVal]) extends Cmd

  final case class SetAction(action: NodeAction) extends Cmd
  final case class Invoke(args: DSAMap, replyTo: ActorRef[ActionResult]) extends Cmd

  final case class SetAttributes(attributes: DSAMap) extends Cmd
  final case class PutAttribute(name: String, value: DSAVal) extends Cmd
  final case class RemoveAttribute(name: String) extends Cmd
  final case object ClearAttributes extends Cmd

  final case class GetChildren(replyTo: ActorRef[NodeRefs]) extends Cmd
  final case class GetChild(name: String, replyTo: ActorRef[Option[NodeRef]]) extends Cmd
  final case class AddChild(name: String, replyTo: ActorRef[NodeRef]) extends Cmd
  final case class RemoveChild(name: String, replyTo: ActorRef[Done]) extends Cmd
  final case class RemoveChildren(replyTo: ActorRef[Done]) extends Cmd

  final case object Stop extends Cmd
}