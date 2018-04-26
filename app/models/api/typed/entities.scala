package models.api.typed

import akka.actor.typed.ActorRef
import models.rpc.DSAValue.{ DSAMap, DSAVal }

/**
 * The internal state of DSA node.
 */
final case class NodeStateInternal(parent:      Option[NodeRef],
                                   displayName: Option[String],
                                   value:       DSAVal,
                                   attributes:  DSAMap) {
  def toPublic(name: String) = DSANodeState(parent, name, displayName, value, attributes)
}

/**
 * State reported by the node to the clients.
 */
final case class DSANodeState(parent:      Option[NodeRef],
                              name:        String,
                              displayName: Option[String],
                              value:       DSAVal,
                              attributes:  DSAMap)

/**
 * The initial state of the node that is being created.
 */
final case class InitState(displayName: Option[String] = None,
                           value:       DSAVal         = null,
                           attributes:  DSAMap         = Map.empty) {
  def toInternal(parent: Option[NodeRef]) = NodeStateInternal(parent, displayName, value, attributes)
}

/**
 * A request that will be followed by a reply
 */
trait Replyable[T] {
  def replyTo: ActorRef[T]
}

/**
 * A request that may be followed by a reply
 */
trait MaybeReplyable[T] {
  def replyTo: Option[ActorRef[T]]
}