package models.api

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, TypedActor}
import akka.cluster.Cluster
import akka.cluster.ddata.{LWWMap, LWWMapKey}
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator._
import akka.util.Timeout
import com.google.inject.Inject
import models.Settings.{Paths, QueryTimeout}
import models.akka.Messages.GetTokens
import models.akka.StandardActions
import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue.StringValue
import models.rpc.{InvokeRequest, RemoveRequest, SetRequest}

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class DistributedNodesRegistry @Inject()(val replicator: ActorRef)(implicit cluster: Cluster, system: ActorSystem) extends Actor with ActorLogging {

  implicit val timeout = Timeout(QueryTimeout)
  implicit val executionContext = system.dispatcher

  import DistributedNodesRegistry._

  val DataKey = LWWMapKey[String, DSANodeDescription]("distributedNodes")

  var registry: Map[String, DSANode] = Map()

  replicator ! Subscribe(DataKey, self)

  override def receive: Receive = {
    case RouteMessage(path, message, sender) =>
      routeMessage(path, message, sender)
    case AddNode(nodeDescription) =>
      sender ! getOrCreateNode(nodeDescription)
    case RemoveNode(path) =>
      sender ! removeNode(path)
    case GetNodes() =>
      sender ! registry
    case GetNode(path: String) =>
      sender ! registry.get(path)
    case GetNodesByDescription(descriptions) =>
      val response: Set[(String, DSANode)] = descriptions
        .map(p => (p.path -> getOrCreateNode(p))).toSet
      sender ! response.toMap
    case resp: UpdateResponse[_] => log.debug("Created on cluster: {}", resp.request)
    case c@Changed(DataKey) =>
      val data = c.get(DataKey)
      val toAdd = data.entries.filterNot(kv => registry.keySet.contains(kv._1)).toList.sortBy(_._1.size)
      val toRemove = registry.keySet.filterNot(data.entries.keySet.contains)

      toAdd.foreach(kv => getOrCreateNode(kv._2))
      toRemove.foreach(removeNode)

      log.debug("Created nodes: {}", toAdd)
      log.debug("Removed nodes: {}", toRemove)
    case GetTokens =>
      log.debug(s"ddNodeRegistry: GetTokens received")

      val response = registry
        .filter{ case (name, node ) => name.startsWith(Paths.Tokens + "/") && node.action.isEmpty }
        .values.toList

      sender ! response
  }

  def removeNode(p: String): Set[String] = {
    val path = validPath(p)
    val pathAndChildren = registry.keySet.filter(_.contains(path))
    if (pathAndChildren.nonEmpty) {
      pathAndChildren.foreach {
        TypedActor(context.system).poisonPill
      }

      registry = registry -- pathAndChildren
      replicator ! Update(DataKey, LWWMap.empty[String, DSANodeDescription], WriteLocal, Some(path)) {
        pathAndChildren.foldLeft[LWWMap[String, DSANodeDescription]](_)(_ - _)
      }

    }

    pathAndChildren
  }

  private def routeMessage(p: String, message: Any, sender: ActorRef): Unit = {
    val path = validPath(p)
    val maybeNode = registry.get(path)

    if (maybeNode.isEmpty) {
      log.error("path {} couldn't be found in distributedDataNode:{}", path)
      log.info("current keys: {}", registry.keySet.mkString("\n -> "))
    }

    maybeNode.foreach { node =>
      val formated = formatMessagePath(message)
      TypedActor(system).getActorRefFor(node).!(formated)(sender)
    }
  }

  private def extractName(path: String): String = {
    val last = path.split("/").last
    if (last.startsWith("$") || last.startsWith("@")) last else ""
  }

  private def formatMessagePath(message: Any): Any = message match {
    case set: SetRequest => set.copy(path = extractName(set.path))
    case rm: RemoveRequest => rm.copy(path = extractName(rm.path))
    case i: InvokeRequest => i.copy(path = extractName(i.path))
    case anyOther => anyOther
  }


  private[this] def getOrCreateNode(path: String, maybeProfile:Option[String] = None, valueType:Option[DSAValueType] = None): DSANode =
    getOrCreateNode(DSANodeDescription.init(path, maybeProfile, valueType))

  /**
    * create node actor for path in parent / root context
    * if parent is not created - creates it
    *
    * @param nodeDescription nodeDescription
    * @return created DSAnode actor reg
    */
  private[this] def getOrCreateNode(nodeDescription: DSANodeDescription): DSANode = {
    val path = validPath(nodeDescription.path)
    val maybeNode = registry.get(path)

    log.debug("GET_OR_CREATE DSANode: {} with profile:{} and type:{}",
      path,
      nodeDescription.profile,
      nodeDescription.valueType)

    maybeNode.getOrElse {
      log.debug("Creating new instance of DSANode: {} with profile:{} and type:{}", path,
        nodeDescription.profile,
        nodeDescription.valueType)
      val pPath = parentPath(path)

      val node: DSANode = createNewNode(nodeDescription, pPath)
      registry = registry + (path -> node)
      replicator ! Update(DataKey, LWWMap.empty[String, DSANodeDescription], WriteLocal)(_ + (path -> nodeDescription))
      node
    }

  }

  private def createNewNode(nodeDescription: DSANodeDescription, pPath: (Option[String], String)) = pPath match {
    case (None, name) => // Root node
      val newOne: DSANode = TypedActor(context)
        .typedActorOf(DistributedDSANode.props( None, new StringValue(""), nodeDescription, self, replicator))

      if (isNotCommon(name)) {
        StandardActions.bindDataRootActions(newOne)
      }
      newOne
    case (Some(parent), name) => { // Children node
      val parentNode: DSANode = registry.get(parent)
        .getOrElse(getOrCreateNode(parent))

      val child = registry.get(nodeDescription.path).getOrElse {
        val newOne: DSANode = TypedActor(context)
          .typedActorOf(DistributedDSANode.props(Some(parentNode), new StringValue(""), nodeDescription, self, replicator))

        parent match {
          // Processing /sys/tokens node - add all required actions
          case parent if (parent + "/" + name).equalsIgnoreCase(Paths.Tokens) =>
            StandardActions.bindTokenGroupNodeActions(newOne)

          // Processing /sys/tokens/<tokenid> - do nothing
          case parent if (parent + "/" + name).startsWith(Paths.Tokens + "/") && isNotCommon(name) =>
            StandardActions.bindTokenNodeActions(newOne)

          // Processing /sys/roles node - add all required actions
          case parent if (parent + "/" + name).equalsIgnoreCase(Paths.Roles) =>
            StandardActions.bindRolesNodeActions(newOne)

          // Processing /sys/roles/<role> - do nothing
          case parent if (parent + "/" + name).startsWith(Paths.Roles + "/")  && isNotCommon(name) =>
            StandardActions.bindRoleNodeActions(newOne)

          // Processing non common nodes
          case parent if isNotCommon(name) =>
            StandardActions.bindDataNodeActions(newOne)

          // Processing any other nodes - do nothing
          case parent =>
        }
        newOne
      }

      parentNode.addChild(name, child)
      child
    }
  }


  private def isNotCommon(name: String): Boolean = !StandardActions.commonActions.contains(name)

  private def validPath(in: String) = if (in.startsWith("/")) in else s"/$in"
}

object DistributedNodesRegistry {

  case class AddNode(nodeDescription: DSANodeDescription)

  case class RouteMessage(path: String, message: Any, sender: ActorRef = ActorRef.noSender)

  case class RemoveNode(path: String)

  case class GetNodes()

  case class GetNodesByDescription(pathes: Seq[DSANodeDescription])

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
