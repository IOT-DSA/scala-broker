package models.actors

import scala.collection.JavaConverters.asScalaBufferConverter

import akka.actor.{ Actor, Props, actorRef2Scala }
import javax.inject.Singleton
import models._
import models.DSAValue.{ BooleanValue, DSAVal, StringValue, array, obj }
import net.sf.ehcache.Ehcache
import play.api.Logger
import play.api.cache.{ CacheApi, EhCacheApi }

/**
 * Services requests that need to be handled by the broker.
 */
class RootNodeActor(settings: Settings, cache: CacheApi) extends Actor {
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
    import settings.Paths._

    cache.set(Root, self)
    cache.set(Data, self)
    cache.set(Defs, self)
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
    case settings.Paths.Defs        => listDefsNodes
    case settings.Paths.Data        => listDataNodes
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
    val config = rows("$is" -> "dsa/broker", "$downstream" -> settings.Paths.Downstream)
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

  val IsNode = "$is" -> StringValue("node")

  def props(settings: Settings, cache: CacheApi) = Props(new RootNodeActor(settings, cache))
}