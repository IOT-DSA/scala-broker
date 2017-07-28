package models.akka.local

import akka.actor.{ Actor, ActorLogging, Props, TypedActor, actorRef2Scala }
import models.{RequestEnvelope, ResponseEnvelope}
import models.api.DSANode
import models.rpc.{ DSAError, DSARequest, DSAResponse }
import models.rpc.DSAValue.{ StringValue, array, obj }
import models.rpc.ListRequest
import models.akka._

/**
 * Handles requests to the top broker node.
 */
class RootNodeActor extends Actor with ActorLogging {
  import RootNodeActor._
  import models.Settings.Nodes._
  import models.rpc.StreamState._
  
  import context.dispatcher

  // create children
  private val downstreamNode = context.actorOf(DownstreamActor.props, Downstream)
  private val dataNode = createDataNode
  private val defsNode = createDefsNode
  private val usersNode = createUsersNode
  private val sysNode = createSysNode
  private val upstreamNode = createUpstreamNode
  private val nodes = Map(
    Downstream -> downstreamNode,
    Upstream -> upstreamNode,
    Data -> dataNode,
    Defs -> defsNode,
    Users -> usersNode,
    Sys -> sysNode)

  /**
   * Registers to receive requests for multiple paths.
   */
  override def preStart() = log.debug("[RootNode] actor initialized")

  /**
   * Handles broker requests.
   */
  def receive = {
    case GetChildren => sender ! nodes

    case env @ RequestEnvelope(reqs) =>
      log.info(s"Received: $env")
      val responses = reqs map processDSARequest
      sender ! ResponseEnvelope(responses)

    case msg @ _ => log.error(s"Unknown message received: $msg")
  }

  /**
   * Static response for LIST / request.
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
   * Processes the DSA request and returns a response. Currently supports only LIST command.
   */
  def processDSARequest(request: DSARequest): DSAResponse = request match {
    case ListRequest(rid, _) =>
      DSAResponse(rid = rid, stream = Some(Closed), updates = Some(rootNodes))

    case req @ _ =>
      log.warning(s"Unsupported request received: $req")
      DSAResponse(rid = req.rid, error = Some(DSAError(msg = Some("Unsupported"))))
  }

  /**
   * Creates a /data node.
   */
  private def createDataNode = {
    val dataNode = TypedActor(context).typedActorOf(DSANode.props(None), Data)
    dataNode.profile = "broker/dataRoot"
    StandardActions.bindDataRootActions(dataNode)
    dataNode
  }

  /**
   * Creates a /defs node hierarchy.
   */
  private def createDefsNode = {
    val defsNode = TypedActor(context).typedActorOf(DSANode.props(None), Defs)
    defsNode.profile = "node"
    defsNode.addChild("profile").foreach { node =>
      node.profile = "static"
      node.addChild("node")
      node.addChild("static")
      node.addChild("dsa").foreach { _.addChild("broker") }
      node.addChild("broker").foreach { node =>
        node.addChild("userNode")
        node.addChild("userRoot")
        node.addChild("dataNode") foreach StandardActions.bindDataNodeActions
        node.addChild("dataRoot") foreach StandardActions.bindDataRootActions
      }
    }
    defsNode
  }

  /**
   * Creates a /users node.
   */
  private def createUsersNode = {
    val usersNode = TypedActor(context).typedActorOf(DSANode.props(None), Users)
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

  /**
   * Creates a /upstream node.
   */
  private def createUpstreamNode = {
    val upstreamNode = TypedActor(context).typedActorOf(DSANode.props(None), Upstream)
    upstreamNode.profile = "node"
    upstreamNode
  }
}

/**
 * Factory for [[RootNodeActor]] instances.
 */
object RootNodeActor {

  case object GetChildren

  /**
   * Creates a new [[RootNodeActor]] props instance.
   */
  def props = Props(new RootNodeActor)
}