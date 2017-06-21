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
import models.akka.{ DownstreamActor, RootNodeActor }
import models.akka.ConnectionInfo
import models.akka.DSLinkActor.StartWSFlow
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
 * Handles main web requests.
 */
@Singleton
class MainController @Inject() (implicit settings: Settings, actorSystem: ActorSystem,
                                materializer: Materializer, cache: CacheApi,
                                life: ApplicationLifecycle) extends Controller {
  private val log = Logger(getClass)

  implicit private val timeout = Timeout(5 seconds)

  private val transformer = jsonMessageFlowTransformer[DSAMessage, DSAMessage]

  private val broker = actorSystem.actorOf(RootNodeActor.props(settings), settings.Nodes.Root)

  private val downstream = {
    val nodes = (broker ? RootNodeActor.GetChildren).mapTo[Map[String, ActorRef]]
    Await.result(nodes.map(_(settings.Nodes.Downstream)), Duration.Inf)
  }

  /**
   * Displays the main app page.
   */
  def index = Action {
    Ok(views.html.index(settings.rootConfig.root))
  }

  /**
   * Displays the configuration.
   */
  def viewConfig = Action {
    Ok(views.html.config(settings.rootConfig.root))
  }

  /**
   * Displays the DSLinks page.
   */
  def findDslinks(regex: String, limit: Int, offset: Int) = Action.async {
    val fLinks = (downstream ? DownstreamActor.FindDSLinks(regex, limit, offset)).mapTo[Iterable[String]]
    val fTotalLinkCount = (downstream ? DownstreamActor.GetDSLinkCount).mapTo[Int]
    for {
      links <- fLinks
      total <- fTotalLinkCount
    } yield Ok(views.html.links(regex, limit, offset, links, total))
  }

  /**
   * Accepts a connection request and sends back the server config JSON.
   */
  def conn = Action(validateJson[ConnectionRequest]) { implicit request =>
    log.debug(s"Conn request received at $request : ${request.body}")

    val ci = buildConnectionInfo(request)
    val linkPath = settings.Paths.Downstream + "/" + ci.linkName
    val json = settings.ServerConfiguration + ("path" -> Json.toJson(linkPath))

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
  private def buildConnectionInfo(request: Request[ConnectionRequest]) = {
    val dsId = getDsId(request)
    val linkName = dsId.substring(0, dsId.length - 44)
    val connReq = request.body
    ConnectionInfo(dsId, linkName, connReq.isRequester, connReq.isResponder)
  }
}