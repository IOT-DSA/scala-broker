package models.sdk.dsa

import akka.actor.typed.ActorRef
import models.rpc.DSARequest
import models.sdk._
import models.sdk.node.NodeEvent.{AttributeEvent, ChildEvent, ConfigEvent}

/**
  * DSA (top level) node commands.
  */
sealed trait DSANodeCommand

/**
  * Available DSA node commands.
  */
object DSANodeCommand {

  final case class ProcessRequests(requests: Iterable[DSARequest], replyTo: DSAListener) extends DSANodeCommand
  final case class ProcessRequest(request: DSARequest, replyTo: DSAListener) extends DSANodeCommand

  final case class GetNodeAPI(replyTo: ActorRef[NodeRef]) extends DSANodeCommand

  final case class DeliverListInfo(path: String, info: ListInfo) extends DSANodeCommand
  final case class DeliverAttributeEvent(path: String, evt: AttributeEvent) extends DSANodeCommand
  final case class DeliverConfigEvent(path: String, evt: ConfigEvent) extends DSANodeCommand
  final case class DeliverChildEvent(path: String, evt: ChildEvent) extends DSANodeCommand
}
