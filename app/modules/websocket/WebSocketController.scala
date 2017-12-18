package modules.websocket

import scala.concurrent.Future
import scala.util.Random

import org.joda.time.DateTime

import akka.actor._
import akka.stream.{ Materializer, OverflowStrategy }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import controllers.BasicController
import javax.inject.{ Inject, Singleton }
import models.Settings
import models.akka.{ ConnectionInfo, DSLinkManager }
import models.metrics.DSLinkEventDao
import models.rpc.DSAMessage
import play.api.cache.SyncCacheApi
import play.api.libs.json.Json
import play.api.mvc.{ ControllerComponents, Request, RequestHeader, WebSocket }
import play.api.mvc.WebSocket.MessageFlowTransformer.jsonMessageFlowTransformer

/**
 * Establishes WebSocket DSLink connections
 */
@Singleton
class WebSocketController @Inject() (actorSystem:    ActorSystem,
                                     materializer:   Materializer,
                                     cache:          SyncCacheApi,
                                     dslinkMgr:      DSLinkManager,
                                     dslinkEventDao: DSLinkEventDao,
                                     cc:             ControllerComponents) extends BasicController(cc) {

  implicit private val ConnectionRequestReads = Json.reads[ConnectionRequest]

  private val transformer = jsonMessageFlowTransformer[DSAMessage, DSAMessage]

  /**
   * Accepts a connection request and sends back the server config JSON.
   */
  def conn = Action(validateJson[ConnectionRequest]) { implicit request =>
    log.debug(s"Conn request received at $request : ${request.body}")

    val ci = buildConnectionInfo(request)
    val linkPath = Settings.Paths.Downstream + "/" + ci.linkName
    val json = Settings.ServerConfiguration + ("path" -> Json.toJson(linkPath))

    val sessionId = ci.linkName + "_" + ci.linkAddress + "_" + Random.nextInt(1000000)

    cache.set(ci.dsId, DSLinkSessionInfo(ci, sessionId))

    dslinkEventDao.saveConnectionEvent(DateTime.now, "handshake", sessionId,
      ci.dsId, ci.linkName, ci.linkAddress, ci.mode, ci.version, ci.compression, ci.brokerAddress)

    log.debug(s"Conn response sent: ${json.toString}")
    Ok(json)
  }

  /**
   * Establishes a WebSocket connection.
   */
  def ws = WebSocket.acceptOrResult[DSAMessage, DSAMessage] { request =>
    log.debug(s"WS request received: $request")
    val dsId = getDsId(request)
    val sessionInfo = cache.get[DSLinkSessionInfo](dsId)
    log.debug(s"Session info retrieved for $dsId: $sessionInfo")

    Future.successful(sessionInfo.map(createWSFlow(_)).toRight(Forbidden))
  }(transformer)

  /**
   * Creates a new WebSocket flow bound to a newly created WSActor.
   */
  private def createWSFlow(sessionInfo: DSLinkSessionInfo,
                           bufferSize:  Int               = 16,
                           overflow:    OverflowStrategy  = OverflowStrategy.dropNew) = {
    import akka.actor.Status._

    val (toSocket, publisher) = Source.actorRef[DSAMessage](bufferSize, overflow)
      .toMat(Sink.asPublisher(false))(Keep.both).run()(materializer)

    val proxy = dslinkMgr.getCommProxy(sessionInfo.ci.linkName)
    val wsProps = WebSocketActor.props(toSocket, proxy,
      WebSocketActorConfig(sessionInfo.ci, sessionInfo.sessionId, Settings.Salt))

    val fromSocket = actorSystem.actorOf(Props(new Actor {
      val wsActor = context.watch(context.actorOf(wsProps, "wsActor"))

      def receive = {
        case Success(_) | Failure(_) => wsActor ! PoisonPill
        case Terminated(_)           => context.stop(self)
        case other                   => wsActor ! other
      }

      override def supervisorStrategy = OneForOneStrategy() {
        case _ => SupervisorStrategy.Stop
      }
    }))

    Flow.fromSinkAndSource[DSAMessage, DSAMessage](
      Sink.actorRef(fromSocket, Success(())),
      Source.fromPublisher(publisher))
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
    val cr = request.body
    new ConnectionInfo(dsId, dsId.substring(0, dsId.length - 44), cr.isRequester, cr.isResponder,
      cr.linkData.map(_.toString), cr.version, cr.formats.getOrElse(Nil), cr.enableWebSocketCompression,
      request.remoteAddress, request.host)
  }
}