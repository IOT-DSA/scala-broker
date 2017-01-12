package controllers

import scala.annotation.implicitNotFound

import akka.actor.ActorSystem
import akka.stream.Materializer
import javax.inject.{ Inject, Singleton }
import models.{ DSAMessage, DSAMessageFormat }
import models.actors.WebSocketActor
import play.api.{ Configuration, Logger }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.libs.json.Reads
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
                                materializer: Materializer) extends Controller {

  private val log = Logger(getClass)

  private val serverConfig = Json.obj(
    "dsId" -> config.getString("broker.dsId"),
    "publicKey" -> config.getString("broker.publicKey"),
    "wsUri" -> "/ws",
    "httpUri" -> "/http",
    "tempKey" -> config.getString("broker.tempKey"),
    "salt" -> config.getString("broker.salt"),
    "version" -> config.getString("broker.version"),
    "updateInterval" -> config.getInt("broker.updateInterval"),
    "format" -> config.getString("broker.format"))

  private val transformer = WebSocket.MessageFlowTransformer.jsonMessageFlowTransformer[DSAMessage, DSAMessage]

  def index = Action { implicit request =>
    Ok(views.html.index(config.underlying.root))
  }

  def conn = Action(validateJson[ConnectionRequest]) { implicit request =>
    log.debug(s"Conn request received: $request")

    // TODO for now, ignoring the request details
    val linkPath = getLinkPath(request)
    val json = serverConfig + ("path" -> Json.toJson(linkPath))

    log.debug(s"Conn response sent: ${json.toString}")
    Ok(json)
  }

  def ws = WebSocket.accept[DSAMessage, DSAMessage] { request =>
    log.debug(s"WS request received: $request")
    ActorFlow.actorRef(WebSocketActor.props)
  }(transformer)

  private def validateJson[A: Reads] = BodyParsers.parse.tolerantJson.validate {
    _.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e)))
  }

  private def getLinkPath(request: Request[ConnectionRequest]) = {
    val dsId = request.getQueryString("dsId").getOrElse("")
    val linkName = dsId.substring(0, dsId.length - 44)
    "/downstream/" + linkName
  }
}