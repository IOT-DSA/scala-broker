package models.akka

import akka.actor._
import akka.pattern.ask
import akka.cluster.singleton._
import akka.util.Timeout
import models.{RequestEnvelope, ResponseEnvelope, Settings}
import models.api.DSANode
import models.api.DistributedNodesRegistry.AddNode
import models.rpc.{DSAError, DSARequest, DSAResponse, ListRequest}
import models.rpc.DSAValue.{StringValue, array, obj}

import scala.concurrent.Await

/**
 * The top broker node, handles requests to `/` path and creates children for `/defs`, `/sys` etc.
 */
class RootNodeActor(ddregistry: Option[ActorRef]) extends Actor with ActorLogging {
  import context.dispatcher
  import models.Settings.Nodes._
  import models.rpc.StreamState._

  // create children
  private val dataNode:DSANode = createDataNode
  private val defsNode:DSANode = createDefsNode
  private val usersNode:DSANode = createUsersNode
  private val sysNode:DSANode = createSysNode

  override def preStart() = log.info("[RootNode] actor initialized")

  override def postStop() = log.info("[RootNode] actor stopped")

  def receive = {
    case env @ RequestEnvelope(reqs) =>
      log.info(s"Received: $env")
      val responses = reqs map processDSARequest
      sender ! ResponseEnvelope(responses)

    case msg @ _ => log.error(s"Unknown message received: $msg")
  }

  private def processDSARequest(request: DSARequest): DSAResponse = request match {
    case ListRequest(rid, "/") =>
      DSAResponse(rid = rid, stream = Some(Closed), updates = Some(rootNodes))

    case req @ _ =>
      log.warning(s"Unsupported request received: $req")
      DSAResponse(rid = req.rid, error = Some(DSAError(msg = Some("Unsupported"))))
  }

  /**
   * Creates the root nodes.
   */
  private val rootNodes = {
    val config = rows(is("dsa/broker"), "$downstream" -> Downstream)
    val children = List(defsNode, dataNode, usersNode, sysNode) map { node =>
      array(node.name, obj(is(node.profile)))
    }
    val stream = rows("upstream" -> obj(IsNode), "downstream" -> obj(IsNode))

    config ++ children ++ stream
  }

  /**
   * Creates a /data node.
   */
  private def createDataNode = {
    implicit val timeout = Timeout(Settings.QueryTimeout)

    val dataNode:DSANode = ddregistry match {
      case Some(registry) =>
        Await.result((registry ? AddNode("/data")).mapTo[DSANode], Settings.QueryTimeout)
      case None =>
        TypedActor(context).typedActorOf(DSANode.props(None), Data)
    }

    dataNode.profile = "broker/dataRoot"
    StandardActions.bindDataRootActions(dataNode)
    dataNode
  }

  /**
   * Creates a /defs node hierarchy.
   */
  private def createDefsNode = {
    val defsNode:DSANode = TypedActor(context).typedActorOf(DSANode.props(None), Defs)
    defsNode.profile = "node"
    defsNode.addChild("profile").foreach { node =>
      node.profile = "static"
      node.addChild("node")
      node.addChild("static")
      node.addChild("dsa").foreach { node =>
        node.addChild("broker")
        node.addChild("link")
      }
      node.addChild("broker").foreach { node =>
        node.addChild("userNode")
        node.addChild("userRoot")
        node.addChild("dataNode") foreach { node =>
          node.profile = "static"
          StandardActions.bindDataNodeActions(node)
        }
        node.addChild("dataRoot") foreach { node =>
          node.profile = "static"
          StandardActions.bindDataRootActions(node)
        }
      }
    }
    defsNode
  }

  /**
   * Creates a /users node.
   */
  private def createUsersNode = {
    val usersNode:DSANode = TypedActor(context).typedActorOf(DSANode.props(None), Users)
    usersNode.profile = "node"
    usersNode
  }

  /**
   * Creates a /sys node.
   */
  private def createSysNode = {
    val sysNode = TypedActor(context).typedActorOf(DSANode.props(None), Sys)
    sysNode.profile = "node"
    sysNode
  }
}

/**
 * Factory for [[RootNodeActor]] instances.
 */
object RootNodeActor {
  import models.Settings.Nodes._

  /**
   * Creates a new instance of [[RootNodeActor]] props.
   */
  def props(ddregistry:Option[ActorRef]) = Props(new RootNodeActor(ddregistry))

  /**
   * Starts a Singleton Manager and returns the cluster-wide unique instance of [[RootNodeActor]].
   */
  def singletonStart(implicit system: ActorSystem, ddregistry:Option[ActorRef]): ActorRef = system.actorOf(
    ClusterSingletonManager.props(
      singletonProps = props(ddregistry),
      terminationMessage = PoisonPill,
      settings = ClusterSingletonManagerSettings(system).withRole("backend")),
    name = Root)

  /**
   * Returns a [[RootNodeActor]] proxy on the current cluster node.
   */
  def singletonProxy(implicit system: ActorSystem): ActorRef = system.actorOf(
    ClusterSingletonProxy.props(
      singletonManagerPath = "/user/" + Root,
      settings = ClusterSingletonProxySettings(system).withRole("backend")),
    name = Root)

  /**
   * Returns a proxy for a child node of the singleton [[RootNodeActor]].
   */
  def childProxy(path: String)(implicit system: ActorSystem): ActorRef = system.actorOf(
    ClusterSingletonProxy.props("/user/" + Root,
      settings = ClusterSingletonProxySettings(system).withSingletonName("singleton" + path)))
}