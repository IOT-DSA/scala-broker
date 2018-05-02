package models.api.typed

import akka.actor.ActorPath
import akka.actor.typed.ActorRef
import models.rpc.DSAValue.{DSAMap, DSAVal}

/**
 * The internal state of DSA node.
 */
final case class DSANodeState(parent:      Option[ActorPath],
                              name:        String,
                              displayName: String,
                              value:       DSAVal,
                              attributes:  DSAMap,
                              children:    List[String])

object DSANodeState {
  val empty = DSANodeState(None, "", "", Nil, Map.empty, List.empty)
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