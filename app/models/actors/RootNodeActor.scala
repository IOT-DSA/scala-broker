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

  private val log = Logger(getClass)

  // a hack to retrieve underlying EhCache until another cache plugin is implemented
  private val ehCache = {
    val ehCacheApiClass = cache.asInstanceOf[EhCacheApi].getClass
    val cacheField = ehCacheApiClass.getDeclaredField("cache")
    cacheField.setAccessible(true)
    cacheField.get(cache).asInstanceOf[Ehcache]
  }

  private val dataNode = createDataNode
  private val defsNode = createDefsNode

  /**
   * Registers to receive requests for multiple paths.
   */
  override def preStart() = {
    import settings.Paths._

    cache.set(Root, self)
    cache.set(Sys, self)
    cache.set(Users, self)
    cache.set(Downstream, self)
    cache.set(Upstream, self)

    log.debug("RootNode actor initialized")
  }

  /**
   * Generates node list as a response for LIST request.
   */
  private val nodesForListPath: PartialFunction[String, List[DSAVal]] = {
    case settings.Paths.Root        => rootNodes
    case settings.Paths.Downstream  => listDownstreamNodes
    case settings.Paths.Upstream    => listUpstreamNodes
    case settings.Paths.Sys         => listSysNodes
    case settings.Paths.Users       => listUsersNodes
    case "/defs/profile/dsa/broker" => rows(IsNode)
  }

  /**
   * Processes the DSA request and returns a response.
   */
  def processDSARequest(request: DSARequest): DSAResponse = request match {
    case ListRequest(rid, path) =>
      nodesForListPath andThen { rows =>
        DSAResponse(rid = rid, stream = Some(Closed), updates = Some(rows))
      } applyOrElse (path, (path: String) => {
        log.error(s"Invalid path specified: $path")
        DSAResponse(rid = rid, error = Some(DSAError(msg = Some(s"Invalid path: $path"))))
      })
    case req @ _ =>
      log.warn(s"Unsupported request received: $req")
      DSAResponse(rid = req.rid, error = Some(DSAError(msg = Some("Unsupported"))))
  }

  /**
   * Handles broker requests.
   */
  def receive = {
    case env @ RequestEnvelope(from, to, _, reqs) => Try {
      log.debug(s"Received $env")
      val responses = reqs map processDSARequest
      val target = cache.get[ActorRef](from).get
      target ! ResponseEnvelope(settings.Paths.Root, from, responses)
    } recover {
      case NonFatal(e) => log.error("Cannot send the response {}", e)
    }
    case msg @ _ => log.error(s"Invalid message received: $msg")
  }

  /**
   * Static response for LIST / request.
   */
  private val rootNodes = {
    val config = rows(is("dsa/broker"), "$downstream" -> settings.Paths.Downstream)
    val children = rows(
      "defs" -> obj(IsNode),
      "data" -> obj("$is" -> "broker/dataRoot"),
      "users" -> obj(IsNode),
      "sys" -> obj(IsNode),
      "upstream" -> obj(IsNode),
      "downstream" -> obj(IsNode))

    config ++ children
  }

  /**
   * Generates response for LIST /downstream request.
   */
  private def listDownstreamNodes = {
    val configs = rows(IsNode, "downstream" -> true)

    val downPrefix = settings.Paths.Downstream + "/"

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
   * Generates response for LIST /sys request.
   */
  private def listSysNodes = rows(IsNode)

  /**
   * Generates response for LIST /users request.
   */
  private def listUsersNodes = rows(IsNode)

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
}

/**
 * Provides contants and factory methods.
 */
object RootNodeActor {
  import models.api.DSAValueType._

  val IsNode = is("node")

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
  def props(settings: Settings, cache: CacheApi, router: MessageRouter) = Props(new RootNodeActor(settings, cache, router))
}