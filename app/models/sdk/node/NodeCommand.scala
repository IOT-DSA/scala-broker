package models.sdk.node

import akka.Done
import akka.actor.typed.ActorRef
import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue.{DSAMap, DSAVal}
import models.sdk.{AttributeListener, ChildListener, ConfigListener, NodeRef, NodeRefs, ValueListener}

/**
  * Command that can be sent to a typed actor representing a DSA node.
  */
sealed trait NodeCommand extends Serializable

/**
  * Available node commands.
  */
object NodeCommand {
  type Cmd = NodeCommand

  /* node state commands */

  final case class GetStatus(replyTo: ActorRef[NodeStatus]) extends Cmd

  final case class SetDisplayName(name: String) extends Cmd
  final case class SetValueType(vType: DSAValueType) extends Cmd
  final case class SetProfile(profile: String) extends Cmd

  final case class SetValue(value: Option[DSAVal]) extends Cmd

  final case class SetAction(action: NodeAction) extends Cmd
  final case class Invoke(args: DSAMap, replyTo: ActorRef[ActionResult]) extends Cmd

  final case class SetAttributes(attributes: DSAMap) extends Cmd
  final case class PutAttribute(name: String, value: DSAVal) extends Cmd
  final case class RemoveAttribute(name: String) extends Cmd
  final case object ClearAttributes extends Cmd

  final case class SetConfigs(configs: DSAMap) extends Cmd
  final case class PutConfig(name: String, value: DSAVal) extends Cmd
  final case class RemoveConfig(name: String) extends Cmd
  final case object ClearConfigs extends Cmd

  final case class GetChildren(replyTo: ActorRef[NodeRefs]) extends Cmd
  final case class GetChild(name: String, replyTo: ActorRef[Option[NodeRef]]) extends Cmd
  final case class AddChild(name: String, replyTo: ActorRef[NodeRef]) extends Cmd
  final case class RemoveChild(name: String, replyTo: ActorRef[Done]) extends Cmd
  final case class RemoveChildren(replyTo: ActorRef[Done]) extends Cmd

  final case object Stop extends Cmd

  /* subscription commands */

  final case class AddValueListener(ref: ValueListener) extends Cmd
  final case class AddAttributeListener(ref: AttributeListener) extends Cmd
  final case class AddConfigListener(ref: ConfigListener) extends Cmd
  final case class AddChildListener(ref: ChildListener) extends Cmd

  final case class RemoveValueListener(ref: ValueListener) extends Cmd
  final case class RemoveAttributeListener(ref: AttributeListener) extends Cmd
  final case class RemoveConfigListener(ref: ConfigListener) extends Cmd
  final case class RemoveChildListener(ref: ChildListener) extends Cmd

  final case object RemoveAllValueListeners extends Cmd
  final case object RemoveAllAttributeListeners extends Cmd
  final case object RemoveAllConfigListeners extends Cmd
  final case object RemoveAllChildListeners extends Cmd
}