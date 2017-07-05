package controllers

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.{ Duration, DurationInt }

import akka.actor.{ ActorRef, ActorSystem }
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import javax.inject.{ Inject, Singleton }
import models.Settings
import models.akka.{ ConnectionInfo, DownstreamActor, RootNodeActor }
import models.akka.DownstreamActor._
import models.akka.DSLinkActor._
import models.rpc.{ DSAMessage, DSAMessageFormat }
import play.api.Logger
import play.api.cache.CacheApi
import play.api.inject.ApplicationLifecycle
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsError, JsValue, Json, Reads }
import play.api.mvc.{ Action, BodyParsers, Controller, Request, RequestHeader, WebSocket }
import play.api.mvc.WebSocket.MessageFlowTransformer.jsonMessageFlowTransformer


/**
 * DSA Client-Broker connection request.
 */
case class ConnectionRequest(publicKey: String, isRequester: Boolean, isResponder: Boolean,
                             linkData: Option[JsValue], version: String, formats: Option[List[String]],
                             enableWebSocketCompression: Boolean)

/**
 * Object passed to a view to supply various page information.
 */
case class ViewConfig(title: String, navItem: String, heading: String, description: String,
                      downCount: Option[Int] = None, upCount: Option[Int] = None)

/**
 * Handles main web requests.
 */
@Singleton
class MainController @Inject() (actorSystem: ActorSystem,
                                materializer: Materializer, cache: CacheApi,
                                life: ApplicationLifecycle) extends Controller {
  private val log = Logger(getClass)

  implicit private val timeout = Timeout(5 seconds)

  private val transformer = jsonMessageFlowTransformer[DSAMessage, DSAMessage]

  private val broker = actorSystem.actorOf(RootNodeActor.props, Settings.Nodes.Root)

  private val downstream = {
    val nodes = (broker ? RootNodeActor.GetChildren).mapTo[Map[String, ActorRef]]
    Await.result(nodes.map(_(Settings.Nodes.Downstream)), Duration.Inf)
  }

  /**
   * Displays the main app page.
   */
  def index = Action.async {
    getDownUpCount map {
      case (down, up) => Ok(views.html.index(Some(down), Some(up)))
    }
  }
  
  /**
   * Displays data explorer.
   */
  def dataExplorer = TODO

  /**
   * Displays the DSLinks page.
   */
  def findDslinks(regex: String, limit: Int, offset: Int) = Action.async {
    import DownstreamActor._

    val fLinkNames = (downstream ? FindDSLinks(regex, limit, offset)).mapTo[Iterable[String]]
    val fDownUpCount = getDownUpCount
    for {
      names <- fLinkNames
      (down, up) <- fDownUpCount
      allLinks <- Future.sequence(names.map(link => (downstream ? GetDSLink(link)).mapTo[Option[ActorRef]]))
      links = allLinks.collect { case Some(ref) => ref }
      infos <- Future.sequence(links.map(link => (link ? GetLinkInfo).mapTo[LinkInfo]))
    } yield Ok(views.html.links(regex, limit, offset, infos, down, Some(down), Some(up)))
  }
  
  /**
   * Displays upstream connections.
   */
  def upstream = TODO
  
  /**
   * Displays the configuration.
   */
  def viewConfig = Action.async {
    getDownUpCount map {
      case (down, up) => Ok(views.html.config(Settings.rootConfig.root, Some(down), Some(up)))
    }
  }
  
  /**
   * Accepts a connection request and sends back the server config JSON.
   */
  def conn = Action(validateJson[ConnectionRequest]) { implicit request =>
    log.debug(s"Conn request received at $request : ${request.body}")

    val ci = buildConnectionInfo(request)
    val linkPath = Settings.Paths.Downstream + "/" + ci.linkName
    val json = Settings.ServerConfiguration + ("path" -> Json.toJson(linkPath))

    cache.set(ci.dsId, ci)

    log.debug(s"Conn response sent: ${json.toString}")
    Ok(json)
  }

  /**
   * Establishes a WebSocket connection.
   */
  def ws = WebSocket.acceptOrResult[DSAMessage, DSAMessage] { request =>
    import DownstreamActor._

    log.debug(s"WS request received: $request")
    val dsId = getDsId(request)
    val connInfo = cache.get[ConnectionInfo](dsId)
    log.debug(s"Conn info retrieved for $dsId: $connInfo")

    connInfo map { ci =>
      for {
        dslink <- (downstream ? GetOrCreateDSLink(ci)).mapTo[ActorRef]
        flow <- (dslink ? StartWSFlow).mapTo[Flow[DSAMessage, DSAMessage, _]]
      } yield Right(flow)
    } getOrElse {
      Future.successful(Left(Forbidden))
    }
  }(transformer)

  /**
   * Validates the JSON and extracts a request message.
   */
  private def validateJson[A: Reads] = BodyParsers.parse.tolerantJson.validate { js =>
    js.validate[A].asEither.left.map { e =>
      log.error(s"Cannot parse connection request JSON: $js. Error info: ${JsError.toJson(e)}")
      BadRequest(JsError.toJson(e))
    }
  }

  /**
   * Extracts `dsId` from the request's query string.
   */
  private def getDsId(request: RequestHeader) = request.queryString("dsId").head

  /**
   * Constructs a connection info instance from the incoming request.
   */
  private def buildConnectionInfo(request: Request[ConnectionRequest]) =
    ConnectionInfo(getDsId(request), request.body)

  /**
   * Returns a future with the number of registered downstream dslinks.
   */
  private def getDownstreamCount = (downstream ? GetDSLinkCount).mapTo[Int]

  /**
   * Returns a future with the number of registered upstream connections.
   */
  private def getUpstreamCount = Future.successful(0)

  /**
   * Combines `getDownstreamCount` and `getUpstreamCount` to produce a future tuple (down, up).
   */
  private def getDownUpCount = {
    val fDown = getDownstreamCount
    val fUp = getUpstreamCount
    for (down <- fDown; up <- fUp) yield (down, up)
  }
}