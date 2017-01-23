package controllers

import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.stream.Materializer
import javax.inject.{ Inject, Singleton }
import models.{ DSAMessage, DSAMessageFormat }
import models.actors.{ ConnectionInfo, DualActor, RequesterActor, ResponderActor, RootNodeActor }
import play.api.{ Configuration, Logger }
import play.api.cache.CacheApi
import play.api.http.websocket.{ CloseCodes, CloseMessage, WebSocketCloseException }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsError, JsValue, Json, Reads, Writes }
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import play.api.libs.streams.ActorFlow
import play.api.mvc.{ Action, BodyParsers, Controller, Request, RequestHeader, WebSocket }
import play.api.mvc.WebSocket.MessageFlowTransformer

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
class MainController @Inject() (implicit config: Configuration, actorSystem: ActorSystem,
                                materializer: Materializer, cache: CacheApi) extends Controller {
  import MainController._

  private val log = Logger(getClass)

  private val serverConfig = buildServerConfig(config)

  private val transformer = jsonMessageFlowTransformer[DSAMessage, DSAMessage]

  // initialize main actors
  actorSystem.actorOf(RootNodeActor.props(cache), "rootNode")

  /**
   * Displays the main app page.
   */
  def index = Action { implicit request =>
    Ok(views.html.index(config.underlying.root))
  }

  /**
   * Accepts a connection request and sends back the server config JSON.
   */
  def conn = Action(validateJson[ConnectionRequest]) { implicit request =>
    log.debug(s"Conn request received: $request")

    val linkPath = getLinkPath(request)
    val json = serverConfig + ("path" -> Json.toJson(linkPath))

    val dsId = getDsId(request)
    val connReq = request.body
    val ci = ConnectionInfo(dsId, connReq.isRequester, connReq.isResponder, linkPath)
    cache.set(dsId, ci)

    log.debug(s"Conn response sent: ${json.toString}")
    Ok(json)
  }

  /**
   * Establishes a WebSocket connection.
   */
  def ws = WebSocket.acceptOrResult[DSAMessage, DSAMessage] { request =>
    log.debug(s"WS request received: $request")
    val dsId = getDsId(request)
    val ci = cache.get[ConnectionInfo](dsId)
    log.debug(s"Conn info retrieved for $dsId: $ci")

    val flow = ci map {
      case ci @ ConnectionInfo(_, true, true, _)  => ActorFlow.actorRef(DualActor.props(_, ci, cache))
      case ci @ ConnectionInfo(_, true, false, _) => ActorFlow.actorRef(RequesterActor.props(_, ci, cache))
      case ci @ ConnectionInfo(_, false, true, _) => ActorFlow.actorRef(ResponderActor.props(_, ci, cache))
    }
    Future.successful(flow.toRight {
      log.error("WS conn rejected: invalid or missing connection info")
      Forbidden
    })
  }(transformer)

  /* misc */

  def jsonMessageFlowTransformer[In: Reads, Out: Writes]: MessageFlowTransformer[In, Out] = {

    val fOut = (out: Out) => {
      val json = Json.toJson(out)
      log.trace("WS Out: " + json)
      json
    }

    val fIn = (json: JsValue) => {
      log.trace("WS In: " + json)
      Json.fromJson[In](json).fold({ errors =>
        val errorInfo = Json.stringify(JsError.toJson(errors))
        log.error(s"Invalid WS message: $json. Errors: $errorInfo")
        throw WebSocketCloseException(CloseMessage(Some(CloseCodes.Unacceptable), errorInfo))
      }, identity)
    }

    WebSocket.MessageFlowTransformer.jsonMessageFlowTransformer.map(fIn, fOut)
  }

  private def validateJson[A: Reads] = BodyParsers.parse.tolerantJson.validate { js =>
    js.validate[A].asEither.left.map { e =>
      log.error(s"Cannot parse connection request JSON: $js. Error info: ${JsError.toJson(e)}")
      BadRequest(JsError.toJson(e))
    }
  }

  private def getDsId(request: RequestHeader) = request.queryString("dsId").head

  private def getLinkPath(request: Request[ConnectionRequest]) = {
    val dsId = getDsId(request)
    val linkName = dsId.substring(0, dsId.length - 44)
    "/downstream/" + linkName
  }
}

/**
 * Helper functions and constants for main application controller.
 */
object MainController {

  /**
   * Builds Server Configuration JSON.
   */
  def buildServerConfig(config: Configuration) = Json.obj(
    "dsId" -> config.getString("broker.dsId"),
    "publicKey" -> config.getString("broker.publicKey"),
    "wsUri" -> "/ws",
    "httpUri" -> "/http",
    "tempKey" -> config.getString("broker.tempKey"),
    "salt" -> config.getString("broker.salt"),
    "version" -> config.getString("broker.version"),
    "updateInterval" -> config.getInt("broker.updateInterval"),
    "format" -> config.getString("broker.format"))
}