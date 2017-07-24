package models.akka.cluster

import akka.actor.{ Actor, ActorLogging, Props, TypedActor, actorRef2Scala }
import models.{ RequestEnvelope, ResponseEnvelope }
import models.akka.StandardActions
import models.api.DSANode
import models.rpc.{ DSAError, DSARequest, DSAResponse, ListRequest }
import models.rpc.DSAValue.{ StringValue, array, obj }

/**
 * Handles requests to the top broker node.
 */
class RootNodeActor extends Actor with ActorLogging {
  import RootNodeActor._
  import context.dispatcher
  import models.Settings.Nodes._
  import models.rpc.StreamState._

  private val dataNode = createDataNode
  private val defsNode = createDefsNode
  private val usersNode = createUsersNode
  private val sysNode = createSysNode
  private val upstreamNode = createUpstreamNode

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
  /**
   * Creates a new instance of [[RootNodeActor]] props.
   */
  def props = Props(new RootNodeActor)
}