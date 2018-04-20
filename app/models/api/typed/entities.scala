package models.api.typed

import akka.actor.typed.ActorRef
import models.rpc.DSAValue.{ DSAMap, DSAVal }

/**
 * The internal state of DSA node.
 */
final case class DSANodeState(parent:      Option[NodeRef],
                              name:        String,
                              displayName: String,
                              value:       DSAVal,
                              attributes:  DSAMap)

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