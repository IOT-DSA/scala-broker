package models.api

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props, TypedActor}
import akka.cluster.Cluster
import akka.cluster.ddata.{GSet, ORSet, ORSetKey}
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator._
import akka.util.Timeout
import models.Settings.QueryTimeout
import models.rpc.DSAValue.{BooleanValue, StringValue}

import scala.annotation.tailrec
import scala.concurrent.Future

class DistributedNodesRegistry(val replicator: ActorRef)(implicit cluster:Cluster, system:ActorSystem) extends Actor with ActorLogging {

  implicit val timeout = Timeout(QueryTimeout)
  implicit val executionContext = system.dispatcher

  import DistributedNodesRegistry._

  val DataKey = ORSetKey[String]("distributedNodes")

  var registry: Map[String, DSANode] = Map()

  replicator ! Subscribe(DataKey, self)

  override def receive: Receive = {
    case AddNode(path) =>
      sender ! getOrCreateNode(path)
    case RemoveNode(path) =>
      sender ! removeNode(path)
    case GetNodes() =>
      sender ! registry
    case GetNode(path:String) =>
      sender ! registry.get(path)
    case GetNodesByPath(pathes) =>
      val response:Set[(String, DSANode)] = pathes.map(p => (p -> getOrCreateNode(p,notifyCluster = false)))
      sender ! response.toMap
    case resp: UpdateResponse[_] => log.debug("Created on cluster: {}", resp.request)
    case c @ Changed(DataKey) =>
      val data = c.get(DataKey)
      val toAdd = data.elements.filterNot(registry.keySet.contains).toList.sortBy(_.size)
      val toRemove = registry.keySet.filterNot(data.elements.contains)

      toAdd.foreach(getOrCreateNode(_, false))
      toRemove.foreach(removeNode(_, false))

      log.debug("Created nodes: {}", toAdd)
      log.debug("Removed nodes: {}", toRemove)
  }

  def removeNode(path:String, notifyCluster:Boolean = true):Set[String] = {
    val pathAndChildren = registry.keySet.filter(_.contains(path))
    if(pathAndChildren.nonEmpty){
      pathAndChildren.foreach{ TypedActor(context.system).poisonPill}

      registry = registry -- pathAndChildren
      if(notifyCluster){
        //maybe would be better to do it with more strict consistency
        replicator ! Update(DataKey,  ORSet.empty[String], writeLocal, Some(path))
        { pathAndChildren.foldLeft[ORSet[String]](_)(_ - _) }
      }

    }

    pathAndChildren
  }

  /**
    * create node actor for path in parent / root context
    * if parent is not created - creates it
    * @param path path
    * @return created DSAnode actor reg
    */
  private[this] def getOrCreateNode(path:String, notifyCluster:Boolean = true): DSANode = {

    val maybeNode = registry.get(path)
    if(maybeNode.isDefined) Future.successful(maybeNode.get)

    val pPath = parentPath(path)

    val node = pPath match {
      case (None, name) =>
        TypedActor(context)
          .typedActorOf(DistributedDSANode.props(path, None, new StringValue(""), self, replicator))
      case (Some(parent), name) => {
        val parentNode: DSANode = registry.get(parent)
          .getOrElse(getOrCreateNode(parent))
        val child:DSANode = TypedActor(context)
          .typedActorOf(DistributedDSANode.props(path, Some(parentNode), new StringValue(""), self, replicator))
        parentNode.addChild(name, child)
        child
      }
    }

    registry = registry + (path -> node)

    if(notifyCluster){
      replicator ! Update(DataKey, ORSet.empty[String], WriteLocal)(_ + path)
    }

    node
  }
}

object DistributedNodesRegistry {

  case class AddNode(path:String)
  case class RemoveNode(path:String)
  case class GetNodes()
  case class GetNodesByPath(pathes:Set[String])
  case class GetNode(path:String)

  def parentPath(path:String):(Option[String], String) = aggregateParent(None, path.split("/").toList.filter(!_.isEmpty))

  @tailrec
  def aggregateParent(parent:Option[String], tail:List[String]):(Option[String], String) = (parent, tail) match {
    case(p:Option[String], Nil) => (p, "")
    case(p:Option[String], head::Nil) => (p, head)
    case (p:Option[String], head::tail) => aggregateParent(p.orElse(Some("")).map(_ + "/" + head), tail)
  }

  def props(replicator:ActorRef, cluster:Cluster, system:ActorSystem) = Props(new DistributedNodesRegistry(replicator)(cluster, system))


}
