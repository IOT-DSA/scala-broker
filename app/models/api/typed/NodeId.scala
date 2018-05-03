package models.api.typed

import java.util.UUID.randomUUID

object NodeId {
  val ROOT_NODE_ID = "ROOT"
  def getRandomNodeId: String = randomUUID().toString
}
