package facades.websocket

import java.net.URL

import scala.concurrent.Future
import scala.util.Random
import org.bouncycastle.jcajce.provider.digest.SHA256
import org.joda.time.DateTime
import akka.Done
import akka.actor._
import akka.pattern.ask
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.routing.Routee
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import controllers.BasicController
import javax.inject.{Inject, Singleton}
import models.Settings
import models.akka.{BrokerActors, ConnectionInfo, DSLinkManager, RichRoutee}
import models.akka.Messages.{GetOrCreateDSLink, RemoveDSLink}
import models.handshake.{LocalKeys, RemoteKey}
import models.metrics.EventDaos
import models.rpc.DSAMessage
import models.util.UrlBase64
import play.api.cache.SyncCacheApi
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.mvc._
import play.api.mvc.WebSocket.MessageFlowTransformer.jsonMessageFlowTransformer
import models.rpc.MsgpackTransformer.{msaMessageFlowTransformer => msgpackMessageFlowTransformer}
//import play.api.mvc.WebSocket.MessageFlowTransformer

/**
 * Establishes WebSocket DSLink connections
 */
@Singleton
class WebSocketController @Inject() (actorSystem:  ActorSystem,
                                     materializer: Materializer,
                                     cache:        SyncCacheApi,
                                     dslinkMgr:    DSLinkManager,
                                     actors:       BrokerActors,
                                     wsc:          WSClient,
                                     keys:         LocalKeys,
                                     eventDaos:    EventDaos,
                                     cc:           ControllerComponents) extends BasicController(cc) {

  type DSAFlow = Flow[DSAMessage, DSAMessage, _]

  import eventDaos._

  implicit private val as = actorSystem

  implicit private val mat = materializer

  implicit private val connReqFormat = Json.format[ConnectionRequest]

  private val jsonTransformer = jsonMessageFlowTransformer[DSAMessage, DSAMessage]
  private val msgpackTransformer = msgpackMessageFlowTransformer[DSAMessage, DSAMessage]

  val transformers = Map(
      "json"->jsonTransformer
    , "msgpack"-> msgpackTransformer
  )

  private def chooseFormat(clientFormats: List[String], serverFormats: List[String]) : String = {
    val mergedFormats = clientFormats intersect serverFormats
    if (mergedFormats.contains("msgpack")) "msgpack" else "json"
  }

  /**
   * Connects to another broker upstream.
   */
  def uplinkHandshake(url: String, name: String) = Action.async {
    val connUrl = new URL(url)

    val publicKey = keys.encodedPublicKey
    val dsId = Settings.BrokerName + "-" + keys.encodedHashedPublicKey

    val cr = ConnectionRequest(publicKey, true, true, None, "1.1.2", Some(List("json")), true)
    val frsp = wsc.url(url)
      .withQueryStringParameters("dsId" -> dsId)
      .post(Json.toJson(cr))

    frsp flatMap { rsp =>
      val serverConfig = Json.parse(rsp.body)

      val tempKey = (serverConfig \ "tempKey").as[String]
      val wsUri = (serverConfig \ "wsUri").as[String]
      val salt = (serverConfig \ "salt").as[String].getBytes("UTF-8")

      val auth = buildAuth(tempKey, salt)
      val wsUrl = s"ws://${connUrl.getHost}:${connUrl.getPort}$wsUri?dsId=$dsId&auth=$auth&format=json"

      uplinkWSConnect(wsUrl, name, dsId)
    }
  }

  /**
   * Initiates a web socket connection with the upstream.
   */
  private def uplinkWSConnect(url: String, name: String, dsId: String) = {
    import Settings.WebSocket._

    val ci = ConnectionInfo(dsId, name, true, true)
    val sessionId = ci.linkName + "_" + ci.linkAddress + "_" + Random.nextInt(1000000)
    val sessionInfo = DSLinkSessionInfo(ci, sessionId)
    val dsaFlow = createWSFlow(sessionInfo, actors.upstream, BufferSize, OnOverflow)

    dsaFlow map { flow =>
      val inFlow = Flow[Message].collect {
        case TextMessage.Strict(s) => Json.parse(s).as[DSAMessage]
      }
      val wsFlow = inFlow.viaMat(flow)(Keep.right).map(msg => TextMessage.Strict(Json.toJson(msg).toString))

      val (upgradeResponse, closed) = Http().singleWebSocketRequest(WebSocketRequest(url), wsFlow)

      val connected = upgradeResponse.map { upgrade =>
        if (upgrade.response.status == StatusCodes.SwitchingProtocols)
          Done
        else
          throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      }

      connected.onComplete(status => log.info(s"Upstream connection completed: $status"))

      Ok("Upstream connection established")
    }
  }

  /**
   * Terminates a connection to upstream.
   */
  def removeUplink(name: String) = Action {
    actors.upstream ! RemoveDSLink(name)
    Ok(s"Uplink '$name' disconnected")
  }

  /**
   * Accepts a connection request and sends back the server config JSON.
   */
  def dslinkHandshake = Action(validateJson[ConnectionRequest]) { implicit request =>
    log.debug(s"Conn request received at $request : ${request.body}")

    val ci = buildConnectionInfo(request)
    val linkPath = Settings.Paths.Downstream + "/" + ci.linkName
    val json = (Settings.ServerConfiguration. + ("path" -> Json.toJson(linkPath))) ++ Json.obj("format" -> ci.resultFormat)

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
  def dslinkWSConnect = acceptOrResult { sessionInfo =>
    import Settings.WebSocket._

    sessionInfo map { si =>
      createWSFlow(si, actors.downstream, BufferSize, OnOverflow) map Right[Result, DSAFlow]
    } getOrElse
      Future.successful(Left[Result, DSAFlow](Forbidden))
  }

  private def acceptOrResult(f: Option[DSLinkSessionInfo] => Future[Either[Result, Flow[DSAMessage, DSAMessage, _]]]): WebSocket = {
    WebSocket { request =>
      log.debug(s"WS request received: $request")
      val dsId = getDsId(request)
      val sessionInfo = cache.get[DSLinkSessionInfo](dsId)
      log.debug(s"Session info retrieved for $dsId: $sessionInfo")

      f(sessionInfo).map(_.right.map(getTransformer(sessionInfo).transform))
    }
  }

  private def getTransformer(sessionInfo : Option[DSLinkSessionInfo]) = {
    val format = sessionInfo.fold("json")(si => {
      chooseFormat(si.ci.formats, (Settings.ServerConfiguration \ "format").as[List[String]])
    })

    transformers.applyOrElse(format, (_ : String) => jsonTransformer)
  }

  /**
   * Creates a new WebSocket flow bound to a newly created WSActor.
   */
  private def createWSFlow(sessionInfo: DSLinkSessionInfo,
                           registry:    ActorRef,
                           bufferSize:  Int,
                           overflow:    OverflowStrategy) = {
    import akka.actor.Status._

    val (toSocket, publisher) = Source.actorRef[DSAMessage](bufferSize, overflow)
      .toMat(Sink.asPublisher(false))(Keep.both).run()(materializer)

    val fRoutee = (registry ? GetOrCreateDSLink(sessionInfo.ci.linkName)).mapTo[Routee]

    fRoutee map { routee =>
      val wsProps = WebSocketActor.props(toSocket, routee, eventDaos,
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
//    val resultFormat = "json"
    val r  = Settings.ServerConfiguration.apply("format").as[Seq[String]]
    val resultFormat = chooseFormat(
      request.body.formats.getOrElse(List("json"))
      , List(r :_*)
    )

    new ConnectionInfo(dsId, dsId.substring(0, dsId.length - 44), cr.isRequester, cr.isResponder,
      cr.linkData.map(_.toString), cr.version, cr.formats.getOrElse(Nil), cr.enableWebSocketCompression,
      request.remoteAddress, request.host, resultFormat = resultFormat)
  }

  /**
   * Builds the authorization hash to be sent to the remote broker.
   */
  private def buildAuth(tempKey: String, salt: Array[Byte]) = {
    val remoteKey = RemoteKey.generate(keys, tempKey)
    val sharedSecret = remoteKey.sharedSecret
    // TODO make more scala-like
    val bytes = Array.ofDim[Byte](salt.length + sharedSecret.length)
    System.arraycopy(salt, 0, bytes, 0, salt.length)
    System.arraycopy(sharedSecret, 0, bytes, salt.length, sharedSecret.length)

    val sha = new SHA256.Digest
    val digested = sha.digest(bytes)
    UrlBase64.encodeBytes(digested)
  }
}