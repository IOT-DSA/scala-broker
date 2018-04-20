package models.api

import akka.actor.typed.ActorRef

/**
 * Helper constants and methods for typed actors.
 */
package object typed {
  type NodeRef = ActorRef[NodeCommand]
  type NodeRefs = Iterable[NodeRef]
}