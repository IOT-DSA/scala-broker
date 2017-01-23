package models.actors

import scala.collection.JavaConverters.asScalaBufferConverter

import akka.actor.{ Actor, Props, actorRef2Scala }
import models._
import models.DSAValue._
import net.sf.ehcache.Ehcache
import play.api.Logger
import play.api.cache.{ CacheApi, EhCacheApi }

/**
 * Services requests that need to be handled by the broker.
 */
class RootNodeActor(cache: CacheApi) extends Actor {
  import RootNodeActor._
  import StreamState._

  private val log = Logger(getClass)

  // a hack to retrieve underlying EhCache until another cache plugin is implemented
  private val ehCache = {
    val ehCacheApiClass = cache.asInstanceOf[EhCacheApi].getClass
    val cacheField = ehCacheApiClass.getDeclaredField("cache")
    cacheField.setAccessible(true)
    cacheField.get(cache).asInstanceOf[Ehcache]
  }

  /**
   * Registers to receive requests for multiple paths.
   */
  override def preStart() = {
    cache.set(RootPath, self)
    cache.set(DataPath, self)
    cache.set(DefsPath, self)
    cache.set(SysPath, self)
    cache.set(UsersPath, self)
    cache.set(DownstreamPath, self)
    cache.set(UpstreamPath, self)

    log.debug("RootNode actor initialized")
  }

  /**
   * Generates node list as a response for LIST request.
   */
  private val nodesForListPath: PartialFunction[String, List[DSAVal]] = {
    case RootPath                   => rootNodes
    case DownstreamPath             => listDownstreamNodes
    case UpstreamPath               => listUpstreamNodes
    case SysPath                    => listSysNodes
    case DefsPath                   => listDefsNodes
    case DataPath                   => listDataNodes
    case UsersPath                  => listUsersNodes
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
        log.error(s"Invalid path specified: path")
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
    case RequestEnvelope(req) => sender ! ResponseEnvelope(processDSARequest(req))
    case msg @ _              => log.error(s"Invalid message received: $msg")
  }

  /**
   * Static response for LIST / request.
   */
  private val rootNodes = {
    val config = rows("$is" -> "dsa/broker", "$downstream" -> "/downstream")
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

    val linkNames = ehCache.getKeys.asScala.toList
    val children = linkNames collect {
      case path: String if path.startsWith(DownstreamPath + "/") =>
        array(path.drop(DownstreamPath.size + 1), obj(IsNode))
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
   * Generates response for LIST /defs request.
   */
  private def listDefsNodes = rows(IsNode)

  /**
   * Generates response for LIST /data request.
   */
  private def listDataNodes = rows(IsNode)

  /**
   * Generates response for LIST /users request.
   */
  private def listUsersNodes = rows(IsNode)

  /**
   * Builds a list of rows, each containing two values.
   */
  private def rows(pairs: (String, DSAVal)*) = pairs map {
    case (key, value) => array(key, value)
  } toList
}

/**
 * Provides contants and factory methods.
 */
object RootNodeActor {
  val RootPath = "/"
  val DataPath = "/data"
  val DefsPath = "/defs"
  val SysPath = "/sys"
  val UsersPath = "/users"
  val DownstreamPath = "/downstream"
  val UpstreamPath = "/upstream"

  val IsNode = "$is" -> StringValue("node")

  def props(cache: CacheApi) = Props(new RootNodeActor(cache))
}