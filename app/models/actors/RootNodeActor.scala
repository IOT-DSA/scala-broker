package models.actors

import scala.collection.JavaConverters.asScalaBufferConverter
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try
import scala.util.control.NonFatal

import akka.actor.{ Actor, ActorRef, Props, TypedActor, actorRef2Scala }
import models.{ MessageRouter, RequestEnvelope, ResponseEnvelope, Settings }
import models.api.{ DSAAction, DSANode }
import models.rpc._
import models.rpc.DSAValue.{ BooleanValue, DSAVal, StringValue, array, obj }
import net.sf.ehcache.Ehcache
import play.api.Logger
import play.api.cache.{ CacheApi, EhCacheApi }

/**
 * Services requests that need to be handled by the broker.
 */
class RootNodeActor(settings: Settings, cache: CacheApi, router: MessageRouter) extends Actor {
  import RootNodeActor._
  import models.rpc.StreamState._
  import settings.Paths._

  private val log = Logger(getClass)

  private val processor = context.actorOf(RRProcessorActor.props(Root, cache))

  // a hack to retrieve underlying EhCache until another cache plugin is implemented
  private val ehCache = {
    val ehCacheApiClass = cache.asInstanceOf[EhCacheApi].getClass
    val cacheField = ehCacheApiClass.getDeclaredField("cache")
    cacheField.setAccessible(true)
    cacheField.get(cache).asInstanceOf[Ehcache]
  }

  // create children
  private val dataNode = createDataNode
  private val defsNode = createDefsNode
  private val usersNode = createUsersNode
  private val sysNode = createSysNode

  /**
   * Registers to receive requests for multiple paths.
   */
  override def preStart() = {
    cache.set(Root, self)
    log.debug("RootNode actor initialized")
  }

  /**
   * Processes the DSA request and returns a response. Currently supports only LIST command.
   */
  def processDSARequest(request: DSARequest): DSAResponse = request match {
    case ListRequest(rid, Root) =>
      DSAResponse(rid = rid, stream = Some(Closed), updates = Some(rootNodes))

    case ListRequest(rid, Downstream) =>
      DSAResponse(rid = rid, stream = Some(Closed), updates = Some(listDownstreamNodes))

    case ListRequest(rid, Upstream) =>
      DSAResponse(rid = rid, stream = Some(Closed), updates = Some(listUpstreamNodes))

    case req @ _ =>
      log.warn(s"Unsupported request received: $req")
      DSAResponse(rid = req.rid, error = Some(DSAError(msg = Some("Unsupported"))))
  }

  /**
   * Handles broker requests.
   */
  def receive = {
    case env @ RequestEnvelope(_, _, reqs) =>
      log.info(s"Received: $env")
      val responses = reqs map processDSARequest
      router.routeResponses(Root, "n/a", responses: _*) recover {
        case NonFatal(e) => log.error(s"Error routing the responses: ${e.getMessage}")
      }

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
   * Generates response for LIST /downstream request.
   */
  private def listDownstreamNodes = {
    val configs = rows(IsNode, "downstream" -> true)

    val downPrefix = Downstream + "/"

    val linkNames = ehCache.getKeys.asScala.toList
    val children = linkNames collect {
      case path: String if path.startsWith(downPrefix) => array(path.drop(downPrefix.size), obj(IsNode))
    }

    configs ++ children
  }

  /**
   * Generates response for LIST /upstream request.
   */
  private def listUpstreamNodes = rows(IsNode)

  /**
   * Creates a /data node.
   */
  private def createDataNode = {
    val dataNode = TypedActor(context).typedActorOf(DSANode.props(router, cache, None), "data")
    dataNode.profile = "broker/dataRoot"
    StandardActions.bindDataRootActions(dataNode)
    dataNode
  }

  /**
   * Creates a /defs node hierarchy.
   */
  private def createDefsNode = {
    val defsNode = TypedActor(context).typedActorOf(DSANode.props(router, cache, None), "defs")
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
    val usersNode = TypedActor(context).typedActorOf(DSANode.props(router, cache, None), "users")
    usersNode.profile = "node"
    usersNode
  }

  /**
   * Creates a /sys node.
   */
  private def createSysNode = {
    val sysNode = TypedActor(context).typedActorOf(DSANode.props(router, cache, None), "sys")
    sysNode.profile = "node"
    sysNode
  }
}

/**
 * Provides contants and factory methods.
 */
object RootNodeActor {
  import models.api.DSAValueType._

  /**
   * A tuple for $is->"node" config.
   */
  val IsNode = is("node")

  /**
   * Creates a tuple for `$is` config.
   */
  def is(str: String): (String, StringValue) = "$is" -> StringValue(str)

  /**
   * Builds a list of rows, each containing two values.
   */
  def rows(pairs: (String, DSAVal)*) = pairs map {
    case (key, value) => array(key, value)
  } toList

  /**
   * Creates an instance of RootNodeActor.
   */
  def props(settings: Settings, cache: CacheApi, router: MessageRouter) =
    Props(new RootNodeActor(settings, cache, router))
}