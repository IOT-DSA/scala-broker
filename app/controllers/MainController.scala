package controllers

import akka.actor.ActorSystem
import akka.stream.Materializer
import javax.inject.{ Inject, Singleton }
import models.{ DSAMessage, DSAMessageFormat }
import models.actors.{ RootNodeActor, WSActorConfig, WebSocketActor, ConnectionInfo }
import play.api.{ Configuration, Logger }
import play.api.cache.CacheApi
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import play.api.libs.streams.ActorFlow
import play.api.mvc._

/**
 * DSA Client-Broker connection request.
 */
case class ConnectionRequest(publicKey: String, isRequester: Boolean, isResponder: Boolean,
                             linkData: Option[JsValue], version: String, formats: List[String],
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

  private val transformer = WebSocket.MessageFlowTransformer.jsonMessageFlowTransformer[DSAMessage, DSAMessage]

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
  def ws = WebSocket.accept[DSAMessage, DSAMessage] { request =>
    log.debug(s"WS request received: $request")
    val dsId = getDsId(request)
    ActorFlow.actorRef(out => WebSocketActor.props(WSActorConfig(out, dsId, cache)))
  }(transformer)

  /* misc */

  private def validateJson[A: Reads] = BodyParsers.parse.tolerantJson.validate {
    _.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e)))
  }

  private def getDsId(request: RequestHeader) = request.queryString("dsId").head

  private def getLinkPath(request: Request[ConnectionRequest]) = {
    val dsId = getDsId(request)
    val linkName = dsId.substring(0, dsId.length - 44)
    "/downstream/" + linkName
  }
}

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