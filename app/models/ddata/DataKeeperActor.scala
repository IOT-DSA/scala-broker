package models.ddata

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.cluster.Cluster
import akka.cluster.ddata.{ORMultiMap, _}
import models.rpc.DSAValue

/**
  * Nodes of the tree.
  */
sealed trait DDVal extends ReplicatedData

/** Tree Node could be a: */
// Value
//case class DDValue(v: DSAValue[_]) extends DDVal
// Subscribers with (sid, path)
//case class DDSubscribers(ss: ORSet[(Int, String)]) extends DDVal
// Attributes
//case class DDAttributes(attrs: ORMap[String, DSAValue[_]]) extends DDVal
// Children
//case class DDChildren(ch: ORMultiMap[String, DDNode]) extends DDVal
// Child
//case class DDNode(ch: DDChildren, v: DDValue, ss: DDSubscribers) extends DDVal

object DataKeeperActor {
  val SEPARATOR = "/"

  case class SetValue[T](key:String, value:T)
  case class GetValue(key:String)
}

/**
 * Actor for store and retrieve data's data from/to tree-like structure.
 */
class DataKeeperActor extends Actor with ActorLogging{
  import DataKeeperActor._

  implicit val cluster = Cluster(context.system)

  val replicator: ActorRef = DistributedData(context.system).replicator

  val storage = ORMultiMap.empty[String, DDVal]

  override def receive = {
    case message =>
      log.info(s"Message to datakeeper $message.")
  }

  override def preStart(): Unit = {
    log.info(s"Node STARTED: $self.toString()")
    super.preStart()
  }
}
