package facades.websocket

import java.net.URL

import akka.{Done, NotUsed}
import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage, WebSocketRequest}
import akka.pattern.ask
import akka.routing.Routee
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, StreamRefs}
import akka.stream.{InvalidSequenceNumberException, Materializer, OverflowStrategy}
import akka.util.ByteString
import controllers.BasicController
import javax.inject.{Inject, Singleton}
import models.Settings
import models.akka.Messages.{AppendDsId2Token, GetOrCreateDSLink, GetTokens, RemoveDSLink}
import models.akka.{BrokerActors, ConnectionInfo, DSLinkManager, RichRoutee}
import models.api.DSANode
import models.handshake.{LocalKeys, RemoteKey}
import models.metrics.Meter
import models.rpc.MsgpackTransformer.{msaMessageFlowTransformer => msgpackMessageFlowTransformer}
import models.rpc.{DSAMessage, EmptyMessage, PingMessage, ResponseMessage}
import models.util.UrlBase64
import org.joda.time.DateTime
import org.velvia.msgpack
import org.velvia.msgpack.PlayJsonCodecs.JsValueCodec
import play.api.cache.SyncCacheApi
import play.api.http.HttpEntity
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSClient
import play.api.mvc.WebSocket.MessageFlowTransformer.jsonMessageFlowTransformer
import play.api.mvc._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random
import scala.util.control.NonFatal

/**
  * Establishes WebSocket DSLink connections
  */
@Singleton
class WebSocketController @Inject()(actorSystem: ActorSystem,
                                    materializer: Materializer,
                                    cache: SyncCacheApi,
                                    actors: BrokerActors,
                                    wsc: WSClient,
                                    keys: LocalKeys,
                                    cc: ControllerComponents,
                                    dslinkMgr: DSLinkManager)
  extends BasicController(cc) with Meter {

  type DSAFlow = Flow[DSAMessage, DSAMessage, _]

  import models.rpc.DSAMessageSerrializationFormat._

  implicit private val as = actorSystem

  implicit private val mat = materializer

  implicit private val connReqFormat = Json.format[ConnectionRequest]

  private val jsonTransformer = jsonMessageFlowTransformer[DSAMessage, DSAMessage]
  private val msgpackTransformer = msgpackMessageFlowTransformer[DSAMessage, DSAMessage]

  val transformers = Map(MSGJSON -> jsonTransformer, MSGPACK -> msgpackTransformer)

  private val saltBase = {
    val bytes = new Array[Byte](12)
    Random.nextBytes(bytes)
    UrlBase64.encodeBytes(bytes)
  }

  private def chooseFormat(clientFormats: List[String], serverFormats: List[String]): String = {
    val mergedFormats = clientFormats intersect serverFormats
    if (mergedFormats.contains(MSGPACK)) MSGPACK else MSGJSON
  }

  /**
    * Connects to another broker upstream via ws.
    */
  def uplinkHandshake(url: String, name: String) = Action.async {
    val connUrl = new URL(url)

    val publicKey = keys.encodedPublicKey
    val dsId = Settings.BrokerName + "-" + keys.encodedHashedPublicKey

    val cr = ConnectionRequest(publicKey, true, true, None, "1.1.2"
      , Some((Settings.ServerConfiguration \ "format").as[List[String]]), true)
    val frsp = wsc.url(url)
      .withQueryStringParameters("dsId" -> dsId)
      .post(Json.toJson(cr))

    frsp flatMap { rsp =>
      val serverConfig = Json.parse(rsp.body)

      val tempKey = (serverConfig \ "tempKey").as[String]
      val wsUri = (serverConfig \ "wsUri").as[String]
      val salt = (serverConfig \ "salt").as[String].getBytes("UTF-8")
      val format = (serverConfig \ "format").as[String]

      val auth = buildAuth(tempKey, salt)
      // Java Url class returns -1 if there is no port in uri string oO
      val wsUrl = if (connUrl.getPort != -1) {
        s"ws://${connUrl.getHost}:${connUrl.getPort}$wsUri?dsId=$dsId&auth=$auth&format=$format"
      } else {
        s"ws://${connUrl.getHost}$wsUri?dsId=$dsId&auth=$auth&format=$format"
      }


      uplinkWSConnect(wsUrl, name, dsId, format)
    }
  }

  /**
    * Initiates a web socket connection with the upstream.
    */
  private def uplinkWSConnect(url: String, name: String, dsId: String, format: String) = {
    import Settings.WebSocket._

    val ci = ConnectionInfo(dsId, name, true, true)
    val sessionId = ci.linkName + "_" + ci.linkAddress + "_" + Random.nextInt(1000000)
    val sessionInfo = DSLinkSessionInfo(ci, sessionId)
    val dsaFlow = createWSFlow(sessionInfo, actors.upstream, BufferSize, OnOverflow)

    dsaFlow flatMap { flow =>

      val tickSource = Source.tick(1 second, 30 seconds, ())
        .zipWithIndex
        .map { case (_, i) => PingMessage(i.toInt) }

      val inFlow = Flow[Message].collect {
        case TextMessage.Strict(s) =>
          val json = Json.parse(s).as[DSAMessage]
          log.debug(s"upstream in: ${json}")
          json
        case BinaryMessage.Strict(m) =>
          val jsVal = msgpack.unpack[JsValue](m.toArray)
          log.debug(s"upstream in: ${jsVal}")
          Json.fromJson[DSAMessage](jsVal).get
      }

      val wsFlow = inFlow.viaMat(flow.merge(tickSource))(Keep.right).map(msg => {
        log.debug(s"upstream ws: ${msg}")
        if (format == MSGPACK) {
          val json = Json.toJson(msg)
          BinaryMessage.Strict(ByteString(msgpack.pack(json)))
        } else {
          TextMessage.Strict(Json.toJson(msg).toString)
        }
      })

      val (upgradeResponse, _) = Http().singleWebSocketRequest(WebSocketRequest(url), wsFlow)

      val connected = upgradeResponse.map { upgrade =>
        if (upgrade.response.status == StatusCodes.SwitchingProtocols)
          Done
        else
          throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      }

      connected.map(tryDone => {
        log.info(s"Upstream connection completed: $tryDone")
        Ok("Upstream connection established")
      }).recover {
        case NonFatal(e) =>
          log.error(s"Upstream connection failed: ${e.getMessage}", e)
          InternalServerError(s"Unable to connect: ${e.getMessage}")
      }
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
  def dslinkHandshake = Action(validateJson[ConnectionRequest]).async { implicit request =>

    Future {
      log.info(s"Conn request received at $request : ${request.body}")

      val ci = buildConnectionInfo(request)

      val json = Settings.ServerConfiguration ++ createHandshakeResponse(ci)

      val sessionId = ci.linkName + "_" + ci.linkAddress + "_" + Random.nextInt(1000000)

      cache.set(ci.dsId, DSLinkSessionInfo(ci, sessionId))

      meterTags(messageTags("handshake", ci): _*)

      log.info(s"Conn response sent: ${json.toString}")
      Ok(json)
    }

  }

  /**
    * Builds a handshake response from the connection info structure.
    *
    * @param ci
    * @return
    */
  private def createHandshakeResponse(ci: ConnectionInfo) = {
    val dsId = Settings.BrokerName + "-" + keys.encodedHashedPublicKey
    val publicKey = keys.encodedPublicKey
    val linkPath = Settings.Paths.Downstream + "/" + ci.linkName
    val json = (Settings.ServerConfiguration + ("path" -> Json.toJson(linkPath))) ++
      Json.obj(
        "format" -> ci.format,
        "dsId" -> dsId,
        "publicKey" -> publicKey,
        "tempKey" -> ci.tempKey,
        "salt" -> ci.salt,
        "path" -> Json.toJson(linkPath)
      )

    json
  }

  /**
    * Establishes a WebSocket connection.
    */
  def dslinkWSConnect = acceptOrResult { sessionInfo =>
    import Settings.WebSocket._

    sessionInfo map { si =>
      appendDsId2Token(si.ci)
      createWSFlow(si, actors.downstream, BufferSize, OnOverflow) map {
        val result = Right[Result, DSAFlow](_)
        log.debug(s"Establishing connection for \nsessionInfo: $si \nresult: $result")
        result
      } recover {
        case NonFatal(e) =>
          log.error(s"Unable to establish ws connection for \nsessionInfo:$si", e)
          Left[Result, DSAFlow](BadRequest)
      }
    } getOrElse {
      log.warn(s"Session info is empty")
      Future.successful(Left[Result, DSAFlow](Forbidden))
    }
  }

  private def acceptOrResult(f: Option[DSLinkSessionInfo] => Future[Either[Result, DSAFlow]]): WebSocket = {
    WebSocket { request =>
      log.debug(s"WS request received: $request")
      val dsId = getDsId(request)
      val clientAuth = getAuth(request)
      val sessionInfo = cache.get[DSLinkSessionInfo](dsId)
      log.debug(s"Session info retrieved for $dsId: $sessionInfo")

      checkToken(sessionInfo) flatMap{
        tokenCheck =>

          val res = if (validateAuth(sessionInfo, clientAuth)) {
            if (tokenCheck)
              f(sessionInfo)
            else {
              val errorString = s"Token check failed"
              log.error(errorString)
              val failedResult = Result(
                new ResponseHeader(UNAUTHORIZED, reasonPhrase = Option(errorString)), HttpEntity.NoEntity
              )
              Future.successful(Left(failedResult))
            }
          } else {
            val errorString = s"Authentication failed in request with dsId: '$dsId', auth value '$clientAuth' is not correct."
            log.error(errorString)
            val failedResult = Result(
              new ResponseHeader(UNAUTHORIZED, reasonPhrase = Option(errorString)), HttpEntity.NoEntity
            )
            Future.successful(Left(failedResult))
          }

          res.map(_.right.map(getTransformer(sessionInfo).transform))

      }
    }
  }

  /**
    * Sends "AppendDsId2Token" message to the corresponding token to associate it with the dsId.
    *
    * @param ci
    */
  private def appendDsId2Token(ci: ConnectionInfo): Unit = {
    log.debug(s"Associating dslink ${ci.dsId} with its token...")
    ci.tokenHash.foreach { token =>
      val tokenId = token.substring(0, 16)
      val dsaPath = Settings.Paths.Tokens + "/" + tokenId
      dslinkMgr.dsaSend(dsaPath, AppendDsId2Token("$dsLinkIds", ci.dsId))
    }
  }

  private def validateAuth(si: Option[DSLinkSessionInfo], clientAuth: Option[String]): Boolean = {
    if (Settings.AllowAllLinks)
      true
    else si.map(_.ci).fold(false) { ci =>
      val localAuth = LocalKeys.saltSharedSecret(ci.salt.getBytes, ci.sharedSecret)
      clientAuth.getOrElse("") == localAuth
    }
  }

  private def checkToken(si: Option[DSLinkSessionInfo]): Future[Boolean] = {
    if (Settings.AllowAllLinks)
      Future.successful(true)
    else si.map(_.ci).fold({
      log.warn("Token is absent!");
      Future.successful(false)
    }) { ci =>
      val token = ci.tokenHash
      val dsaPath = "/sys/tokens"

      if (token.isEmpty)
        log.warn("Client token is absent!")

      val fActiveTokens = dslinkMgr.dsaAsk(dsaPath, GetTokens).mapTo[List[DSANode]]

      val fExist = fActiveTokens.flatMap{ listNodes =>
        log.debug("Available tokens are: " + listNodes.toString)
        val tokenId = token.flatMap(a => Option(a.substring(0, 16)))

        listNodes foreach (item => log.debug("node name is: " + item.name))

        val r = listNodes.indexWhere(tokenId.isDefined && _.name.equals(tokenId.get))

        r match {
          case -1 => Future.successful(false)
          case i =>
            val tokenNode = listNodes(i)
            val check = for {
              checkCount <- checkTokenCount(tokenId.get, tokenNode)
              checkDate <- checkTokenDates(tokenId.get, tokenNode)
            } yield checkCount && checkDate
            check
        }
      }

      // TODO: try to avoid Await here
      fExist
    }
  }

  def checkTokenDates(tokenId: String, tokenNode: DSANode) = {
    val dateRange = tokenNode.config("$timeRange")

    val check = dateRange map {
      case None =>
        log.warn("TimeRange is absent in the token, pass the token")
        true
      case Some(x) =>
        val r = try {
          val dates = x.toString.split('/')
          val date1 = DateTime.parse(dates(0))
          val date2 = DateTime.parse(dates(1))

          val currentDate = DateTime.now()

          val c = currentDate.compareTo(date1) >= 0 && currentDate.compareTo(date2) <= 0
          if (c) log.warn(s"The token is out of the dateRange $date1 - $date2")
          c
        } catch {
          case e: Exception =>
            log.error(e.getMessage)
            false
        }
        r
    }

    check
  }

  private def checkTokenCount(tokenId: String, tokenNode: DSANode) = {
    val fCountItem = tokenNode.config("$countItem")
    val fCount = tokenNode.config("$count")

    val fCheck = for {
      countItem <- fCountItem
      count <- fCount
    } yield {
      val iCountItem = countItem.getOrElse(0).toString.toInt
      tokenNode.addConfigs("$countItem" -> iCountItem.toString)

      val countCheck = iCountItem <= count.getOrElse(0).toString.toInt

      if (!countCheck)
        log.error(s"DSLink with tokenId $tokenId distinguish limit of connection to the broker. DS link is not allowd to connect")

      countCheck
    }
    fCheck
  }

  private def getTransformer(sessionInfo: Option[DSLinkSessionInfo]) = {
    val format = sessionInfo.fold(MSGJSON)(si => {
      chooseFormat(si.ci.clientFormats, (Settings.ServerConfiguration \ "format").as[List[String]])
    })

    transformers.applyOrElse(format, (_: String) => jsonTransformer)
  }

  /**
    * Creates a new WebSocket flow bound to a newly created WSActor.
    */
  private def createWSFlow(sessionInfo: DSLinkSessionInfo,
                           registry: ActorRef,
                           bufferSize: Int,
                           overflow: OverflowStrategy) = {

    val config = WebSocketActorConfig(sessionInfo.ci, sessionInfo.sessionId, Settings.Salt)
    val wsProps = WebSocketActor.props(registry, config)



    val watcher = actorSystem.actorOf(Props(new Actor {

      val wsActor = context.watch(context.actorOf(wsProps, "wsActor"))

      def receive = {
        case _ => sender ! wsActor
      }

      override def supervisorStrategy = OneForOneStrategy() {
        case any =>
          log.error("restarting websocket: {}", any)
          SupervisorStrategy.stop
      }
    }))

    for{
      wsActor <- (watcher ? "GivMeActorPlease").mapTo[ActorRef]
      flow <- (wsActor ? StreamRequest()).mapTo[Flow[DSAMessage, DSAMessage, NotUsed]]
    } yield {

      log.info(s"starting flow: ${flow}")
      flow

    }

  }

  /**
    * Extracts `dsId` from the request's query string.
    */
  private def getDsId(request: RequestHeader) = request.queryString("dsId").head

  /**
    * Extracts "auth" parameter from URL
    */
  private def getAuth(request: RequestHeader): Option[String] = request.queryString.get("auth").flatMap(_.headOption)

  /**
    * Extracts security token from URL
    */
  private def getToken(request: RequestHeader): Option[String] = request.queryString.get("token").flatMap(_.headOption)

  /**
    * Constructs a connection info instance from the incoming request.
    */
  private def buildConnectionInfo(request: Request[ConnectionRequest]) = {
    val dsId = getDsId(request)
    val cr = request.body
    val availableFormats = Settings.ServerConfiguration("format").as[Seq[String]]
    val selectedFormat = chooseFormat(cr.formats.getOrElse(List(MSGJSON)), List(availableFormats: _*))
    val tempKeys = LocalKeys.generate
    val tempKey = tempKeys.encodedPublicKey
    val sharedSecret = RemoteKey.generate(tempKeys, cr.publicKey).sharedSecret

    val localSalt = saltBase + Random.nextInt(Int.MaxValue).toHexString
    val tokenHash = getToken(request)

    ConnectionInfo(dsId, dsId.substring(0, dsId.length - 44), cr.isRequester, cr.isResponder,
      cr.linkData.map(_.toString), cr.version, cr.formats.getOrElse(Nil), cr.enableWebSocketCompression,
      request.remoteAddress, request.host, format = selectedFormat, tempKey = tempKey,
      sharedSecret = sharedSecret, salt = localSalt, tokenHash = tokenHash)
  }

  /**
    * Builds the authorization hash to be sent to the remote broker.
    */
  private def buildAuth(tempKey: String, salt: Array[Byte]) = {
    val remoteKey = RemoteKey.generate(keys, tempKey)
    val sharedSecret = remoteKey.sharedSecret

    LocalKeys.saltSharedSecret(salt, sharedSecret)
  }
}
