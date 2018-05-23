package models.api

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill}
import akka.cluster.ddata.ORSetKey
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import models.Settings.QueryTimeout

import scala.annotation.tailrec
import scala.concurrent.Future

class DistributedNodesRegistry(val replicator:ActorRef) extends Actor with ActorLogging {

  implicit val timeout = Timeout(QueryTimeout)

  import DistributedNodesRegistry._

  val DataKey = ORSetKey[String]("distributedNodes")

  var registry: Map[String, ActorRef] = Map()

  override def receive: Receive = {
    case AddNode(path) => pipe(addNodeAndParents(path)).to(sender())
    case RemoveNode(path) => sender ! removeNode(path)
  }

  //create node and it's parents if not exisits
  def addNodeAndParents(path:String): Future[ActorRef] = {

    futureNode
  }

  def removeNode(path:String):Option[String] = registry.get(path) map {node => node ! PoisonPill; path}

  /**
    * create node actor for path in parent / root context
    * if parent is not created - creates it
    * @param path path
    * @return created DSAnode actor reg
    */
  private[this] def getOrCreateNode(path:String): Future[ActorRef] = {

    val maybeNode = registry.get(path)
    if(maybeNode.isDefined) Future.successful(maybeNode.get)

    val pPath = parentPath(path)

    pPath match {
      case (None, name) =>
        (self ? CreateChild(name)).mapTo[ActorRef]
      case (Some(parent), name) =>

        val parentActor:Future[ActorRef] = registry.get(parent)
          .map(Future.successful(_))
          .getOrElse(getOrCreateNode(parent))

        parentActor.flatMap( p => (p ? CreateChild(name)).mapTo[ActorRef])
    }
  }
}

object DistributedNodesRegistry{

  case class AddNode(path:String)
  case class RemoveNode(path:String)
  case class CreateChild(name:String)

  def parentPath(path:String):(Option[String], String) = aggregateParent(None, path.split("/").toList.filter(!_.isEmpty))

  @tailrec
  def aggregateParent(parent:Option[String], tail:List[String]):(Option[String], String) = (parent, tail) match {
    case(p:Option[String], Nil) => (p, "")
    case(p:Option[String], head::Nil) => (p, head)
    case (p:Option[String], head::tail) => aggregateParent(p.orElse(Some("")).map(_ + "/" + head), tail)
  }


}
