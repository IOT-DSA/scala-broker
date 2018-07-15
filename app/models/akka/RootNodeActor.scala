package models.akka

import akka.actor._
import akka.cluster.singleton._
import models.akka.StandardActions.bindTokenNodeActions
import models.{RequestEnvelope, ResponseEnvelope}
import models.api.{ActionContext, DSANode}
import models.rpc.{DSAError, DSARequest, DSAResponse, ListRequest}
import models.rpc.DSAValue.{StringValue, array, obj}
import models.util.DsaToAkkaCoder._

/**
 * The top broker node, handles requests to `/` path and creates children for `/defs`, `/sys` etc.
 */
class RootNodeActor extends Actor with ActorLogging {
  import context.dispatcher
  import models.Settings.Nodes._
  import models.rpc.StreamState._

  // create children
  private val dataNode = createDataNode
  private val defsNode = createDefsNode
  private val usersNode = createUsersNode
  private val sysNode = createSysNode

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
    val dataNode:DSANode = TypedActor(context).typedActorOf(DSANode.props(None), Data)
    dataNode.profile = "broker/dataRoot"
    StandardActions.bindDataRootActions(dataNode)
    dataNode
  }

  /**
   * Creates a /defs node hierarchy.
   */
  private def createDefsNode = {
    val defsNode: DSANode = TypedActor(context).typedActorOf(DSANode.props(None), Defs)
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
    val usersNode: DSANode = TypedActor(context).typedActorOf(DSANode.props(None), Users)
    usersNode.profile = "node"
    usersNode
  }

  /**
   * Creates a /sys node.
   */
  private def createSysNode = {
    val sysNode: DSANode = TypedActor(context).typedActorOf(DSANode.props(None), Sys)
    sysNode.profile = "broker/sysRoot"

    // The method call was commented as it does need in cluster mode.
    // TODO: Uncomment it in future
//    RootNodeActor.createTokensNode(sysNode)

    // Add root node for roles
    // The method call was commented as it does need in cluster mode.
    // TODO: Uncomment it in future
//    RootNodeActor.createRolesNode(sysNode)

    sysNode
  }
}

/**
 * Factory for [[RootNodeActor]] instances.
 */
object RootNodeActor {
  import models.Settings.Nodes._
  import scala.concurrent.ExecutionContext.Implicits.global

  /**
   * Creates a new instance of [[RootNodeActor]] props.
   */
  def props() = Props(new RootNodeActor())

  /**
   * Starts a Singleton Manager and returns the cluster-wide unique instance of [[RootNodeActor]].
   */
  def singletonStart(implicit system: ActorSystem): ActorRef = system.actorOf(
    ClusterSingletonManager.props(
      singletonProps = props(),
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
  def childProxy(dsaPath: String)(implicit system: ActorSystem): ActorRef = system.actorOf(
    ClusterSingletonProxy.props("/user/" + Root,
      settings = ClusterSingletonProxySettings(system).withSingletonName("singleton" + dsaPath.forAkka)))

  /**
    * Create Roles nodes in the parent node (usualy in /sys)
    * The roles are related to tokens and permission
    * TODO: Remove it to Token's class
    * @param parentNode
    */
  private def createRolesNode(parentNode: DSANode) = {
    parentNode.addChild("roles").foreach { node =>
      node.profile = "node"
      node.displayName = "Roles"
      StandardActions.bindRolesNodeActions(node)
    }
  }

  /**
    * Add root node (aka tokens/GroupToken node) for all tokens
    * In Dart impl GroupToken node is used for grouping tokens by a user
    * I.e. GroupToken is just "username". Since users are skipped (as Rick said)
    * , we are not creating user's groupToken
    *
    **/
  private def createTokensNode(parentNode: DSANode) = {
    parentNode.addChild("tokens").foreach { node =>
      node.profile = "node"
      node.displayName = "Tokens"
      StandardActions.bindTokenGroupNodeActions(node)

      // Add default token
      val tokenId = "1234567891234567"
      node.addChild(tokenId) foreach { child =>
        child.profile = "broker/TokenNode"
        child.addConfigs(
          ("group" -> "config")
          , ("token" -> "tokenId")
          , ("is" -> "broker/Token")
        )
        child.addConfigs(
          ("$$count" -> null)
          , ("$$managed" -> false)
          , ("$$maxSession" -> null)
          , ("$$timeRange" -> null)

        )
        bindTokenNodeActions(child)
      }
    }
  }

}