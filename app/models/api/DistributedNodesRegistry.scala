package models.api

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, TypedActor}
import akka.cluster.Cluster
import akka.cluster.ddata.{ORSet, ORSetKey}
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator._
import akka.util.Timeout
import com.google.inject.Inject
import models.Settings.QueryTimeout
import models.akka.StandardActions
import models.rpc.DSAValue.StringValue
import models.rpc.{InvokeRequest, RemoveRequest, SetRequest}

import scala.annotation.tailrec
import scala.concurrent.Future

class DistributedNodesRegistry @Inject()(val replicator: ActorRef)(implicit cluster: Cluster, system: ActorSystem) extends Actor with ActorLogging {

  implicit val timeout = Timeout(QueryTimeout)
  implicit val executionContext = system.dispatcher

  import DistributedNodesRegistry._

  val DataKey = ORSetKey[String]("distributedNodes")

  var registry: Map[String, DSANode] = Map()

  replicator ! Subscribe(DataKey, self)

  override def receive: Receive = {
    case RouteMessage(path, message, sender) =>
      routeMessage(path, message, sender)
    case AddNode(path) =>
      sender ! getOrCreateNode(path)
    case RemoveNode(path) =>
      sender ! removeNode(path)
    case GetNodes() =>
      sender ! registry
    case GetNode(path: String) =>
      sender ! registry.get(path)
    case GetNodesByPath(pathes) =>
      val response: Set[(String, DSANode)] = pathes.map(p => (p -> getOrCreateNode(p)))
      sender ! response.toMap
    case resp: UpdateResponse[_] => log.debug("Created on cluster: {}", resp.request)
    case c@Changed(DataKey) =>
      val data = c.get(DataKey)
      val toAdd = data.elements.filterNot(registry.keySet.contains).toList.sortBy(_.size)
      val toRemove = registry.keySet.filterNot(data.elements.contains)

      toAdd.foreach(getOrCreateNode(_))
      toRemove.foreach(removeNode)

      log.debug("Created nodes: {}", toAdd)
      log.debug("Removed nodes: {}", toRemove)
  }

  def removeNode(p: String): Set[String] = {
    val path = validPath(p)
    val pathAndChildren = registry.keySet.filter(_.contains(path))
    if (pathAndChildren.nonEmpty) {
      pathAndChildren.foreach {
        TypedActor(context.system).poisonPill
      }

      registry = registry -- pathAndChildren
      replicator ! Update(DataKey, ORSet.empty[String], WriteLocal, Some(path)) {
        pathAndChildren.foldLeft[ORSet[String]](_)(_ - _)
      }

    }

    pathAndChildren
  }

  private def routeMessage(p:String, message:Any, sender:ActorRef): Unit = {
    val path = validPath(p)
    val maybeNode = registry.get(path)

    if(maybeNode.isEmpty) {
      log.error("path {} couldn't be found in distributedDataNode:{}", path)
      log.info("current keys: {}", registry.keySet.mkString("\n -> "))
    }


    maybeNode.foreach{ node =>
      val formated = formatMessagePath(message)
      TypedActor(system).getActorRefFor(node).!(formated)(sender)
    }
  }

  private def extractName(path:String):String = {
    val last = path.split("/").last
    if(last.startsWith("$") || last.startsWith("@")) last else ""
  }

  private def formatMessagePath(message:Any):Any = message match {
    case set:SetRequest => set.copy(path = extractName(set.path))
    case rm:RemoveRequest => rm.copy(path = extractName(rm.path))
    case i:InvokeRequest => i.copy(path = extractName(i.path))
    case anyOther => anyOther
  }


  /**
    * create node actor for path in parent / root context
    * if parent is not created - creates it
    *
    * @param p path
    * @return created DSAnode actor reg
    */
  private[this] def getOrCreateNode(p: String): DSANode = {
    val path = validPath(p)
    val maybeNode = registry.get(path)
    if (maybeNode.isDefined) Future.successful(maybeNode.get)

    val pPath = parentPath(path)

    val node = pPath match {
      case (None, name) =>
        val newOne = TypedActor(context)
          .typedActorOf(DistributedDSANode.props(path, None, new StringValue(""), self, replicator))
        if(isNotCommon(name)){
          StandardActions.bindDataRootActions(newOne)
        }
        newOne
      case (Some(parent), name) => {
        val parentNode: DSANode = registry.get(parent)
          .getOrElse(getOrCreateNode(parent))
        val child = registry.get(path).getOrElse {
          val newOne = TypedActor(context)
            .typedActorOf(DistributedDSANode.props(path, Some(parentNode), new StringValue(""), self, replicator))
          if(isNotCommon(name)){
            StandardActions.bindDataNodeActions(newOne)
          }
          newOne
        }
        parentNode.addChild(name, child)
        child
      }
    }

    registry = registry + (path -> node)

    replicator ! Update(DataKey, ORSet.empty[String], WriteLocal)(_ + path)

    node
  }

  private def isNotCommon(name:String):Boolean = !StandardActions.commonActions.contains(name)

  private def validPath(in:String) = if(in.startsWith("/")) in else s"/$in"
}

object DistributedNodesRegistry {

  case class AddNode(path: String)

  case class RouteMessage(path:String, message:Any, sender:ActorRef = ActorRef.noSender)

  case class RemoveNode(path: String)

  case class GetNodes()

  case class GetNodesByPath(pathes: Set[String])

  case class GetNode(path: String)

  def parentPath(path: String): (Option[String], String) = aggregateParent(None, path.split("/").toList.filter(!_.isEmpty))

  @tailrec
  def aggregateParent(parent: Option[String], tail: List[String]): (Option[String], String) = (parent, tail) match {
    case (p: Option[String], Nil) => (p, "")
    case (p: Option[String], head :: Nil) => (p, head)
    case (p: Option[String], head :: tail) => aggregateParent(p.orElse(Some("")).map(_ + "/" + head), tail)
  }

  def props(replicator: ActorRef, cluster: Cluster, system: ActorSystem) = Props(new DistributedNodesRegistry(replicator)(cluster, system))


}
