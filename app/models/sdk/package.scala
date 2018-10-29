package models

import _root_.akka.actor.typed.ActorRef

/**
  * Helper constants and methods for typed actors.
  */
package object sdk {
  type NodeRef = ActorRef[NodeCommand]
  type NodeRefs = Iterable[NodeRef]
}