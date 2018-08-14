package facades.websocket

import java.net.URL

import play.api.http.HttpEntity

import scala.concurrent.Future
import scala.util.Random
import org.velvia.msgpack.PlayJsonCodecs.JsValueCodec
import akka.Done
import akka.actor._
import akka.pattern.ask
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage, WebSocketRequest}
import akka.routing.Routee
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import controllers.BasicController
import javax.inject.{Inject, Singleton}
import models.Settings
import models.akka.{BrokerActors, ConnectionInfo, DSLinkManager, Messages, RichRoutee, RootNodeActor}
import models.akka.Messages.{AppendDsId2Token, GetOrCreateDSLink, GetTokens, RemoveDSLink}
import models.akka.QoSState.SubscriptionSourceMessage
import models.api.DSANode
import models.handshake.{LocalKeys, RemoteKey}
import models.metrics.Meter
import models.rpc.{DSAMessage, PingMessage}
import models.util.UrlBase64
import play.api.cache.SyncCacheApi
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSClient
import play.api.mvc._
import play.api.mvc.WebSocket.MessageFlowTransformer.jsonMessageFlowTransformer
import models.rpc.MsgpackTransformer.{msaMessageFlowTransformer => msgpackMessageFlowTransformer}
import org.joda.time.DateTime
import org.velvia.msgpack

import scala.util.control.NonFatal
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Establishes WebSocket DSLink connections
 */
@Singleton
class WebSocketController @Inject() (actorSystem:  ActorSystem,
                                     materializer: Materializer,
                                     cache:        SyncCacheApi,
                                     actors:       BrokerActors,
                                     wsc:          WSClient,
                                     keys:         LocalKeys,
                                     cc:           ControllerComponents,
                                     dslinkMgr:    DSLinkManager
                                    )
  extends BasicController(cc)
  with Meter {

  type DSAFlow = Flow[DSAMessage, DSAMessage, _]

  import models.rpc.DSAMessageSerrializationFormat._

  implicit private val as = actorSystem

  implicit private val mat = materializer

  implicit private val connReqFormat = Json.format[ConnectionRequest]

  private val jsonTransformer = jsonMessageFlowTransformer[DSAMessage, DSAMessage]
  private val msgpackTransformer = msgpackMessageFlowTransformer[DSAMessage, DSAMessage]

  val transformers = Map(
      MSGJSON-> jsonTransformer
    , MSGPACK-> msgpackTransformer
  )

  private val saltBase: String = UrlBase64.encodeBytes(Array.fill[Byte](12){Random.nextInt(255).toByte})

  private def chooseFormat(clientFormats: List[String], serverFormats: List[String]) : String = {
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
      val wsUrl = if(connUrl.getPort != -1){
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
  private def uplinkWSConnect(url: String, name: String, dsId: String, format:String) = {
    import Settings.WebSocket._

    val ci = ConnectionInfo(dsId, name, true, true)
    val sessionId = ci.linkName + "_" + ci.linkAddress + "_" + Random.nextInt(1000000)
    val sessionInfo = DSLinkSessionInfo(ci, sessionId)
    val dsaFlow = createWSFlow(sessionInfo, actors.upstream, BufferSize, OnOverflow)

    dsaFlow flatMap { flow =>

      val tickSource = Source.tick(1 second, 30 seconds, ())
        .zipWithIndex
        .map{case (_, i) => PingMessage(i.toInt)}

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
        if(format == MSGPACK) {
          val json = Json.toJson(msg)
          BinaryMessage.Strict(ByteString(msgpack.pack(json)))
        } else {
          TextMessage.Strict(Json.toJson(msg).toString)
        }

      })

      val (upgradeResponse, closed) = Http()
        .singleWebSocketRequest(WebSocketRequest(url), wsFlow)

      val connected = upgradeResponse.map { upgrade =>
        if (upgrade.response.status == StatusCodes.SwitchingProtocols)
          Done
        else
          throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      }

      connected.map(tryDone => {
        log.info(s"Upstream connection completed: $tryDone")
        Ok("Upstream connection established")
      }).recover{
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
  def dslinkHandshake = Action(validateJson[ConnectionRequest]) { implicit request =>
    log.debug(s"Conn request received at $request : ${request.body}")

    val ci = buildConnectionInfo(request)

    val json = Settings.ServerConfiguration ++ createHandshakeResponse(ci)

    val sessionId = ci.linkName + "_" + ci.linkAddress + "_" + Random.nextInt(1000000)

    cache.set(ci.dsId, DSLinkSessionInfo(ci, sessionId))

    meterTags(messageTags("handshake", ci):_*)

    log.debug(s"Conn response sent: ${json.toString}")
    Ok(json)
  }

  private def createHandshakeResponse(ci: ConnectionInfo) = {
    val localKeys = keys
    val dsId =  Settings.BrokerName + "-" + localKeys.encodedHashedPublicKey
    val publicKey = localKeys.encodedPublicKey
    val linkPath = Settings.Paths.Downstream + "/" + ci.linkName
    val json = (Settings.ServerConfiguration + ("path" -> Json.toJson(linkPath))) ++
      Json.obj(
        "format" -> ci.resultFormat
        , "dsId" -> dsId
        , "publicKey" -> publicKey
        , "tempKey" -> ci.tempKey
        , "salt" -> ci.salt
        , "path" -> Json.toJson(linkPath)
      )

    json
  }

  /**
   * Establishes a WebSocket connection.
   */
  def dslinkWSConnect = acceptOrResult { sessionInfo =>
    import Settings.WebSocket._

    sessionInfo map { si =>
      appendDsId2Token(si)
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

      // TODO: reimplement it in scala style
      val res =
        if (validateAuth(sessionInfo, clientAuth)) {
          if (checkToken(sessionInfo))
            f(sessionInfo)
          else {
            val errorString = s"Token check failed"
            log.error(errorString)
            val failedResult = Result(new ResponseHeader(UNAUTHORIZED, reasonPhrase = Option(errorString))
              , HttpEntity.NoEntity
            )
            Future.successful(Left(failedResult))
          }
        } else {
          val errorString = s"Authentication failed in request with dsId: '$dsId', auth value '$clientAuth' is not correct."
          log.error(errorString)
          val failedResult = Result(new ResponseHeader(UNAUTHORIZED, reasonPhrase = Option(errorString))
                                    , HttpEntity.NoEntity
                                   )
          Future.successful(Left(failedResult))
        }

      res.map(_.right.map(getTransformer(sessionInfo).transform))
    }
  }

  private def appendDsId2Token(si: DSLinkSessionInfo) : Unit = {
    log.debug("Try to append dsLink to his token ...")
    si.ci.tokenHash.foreach { token =>
      val tokenId = token.substring(0, 16)
      val dsaPath = Settings.Paths.Tokens + "/" + tokenId
      dslinkMgr.dsaSend(dsaPath, AppendDsId2Token("$dsLinkIds", si.ci.dsId))
    }
  }

  private def validateAuth(si: Option[DSLinkSessionInfo], clientAuth: Option[String]): Boolean = {
    if (Settings.AllowAllLinks)
      return true

    si.map(_.ci).fold(false) { ci =>
      val localAuth = LocalKeys.saltSharedSecret(ci.salt.getBytes, ci.sharedSecret)
      clientAuth.getOrElse("") == localAuth
    }
  }

  private def checkToken(si: Option[DSLinkSessionInfo]): Boolean = {
    if (Settings.AllowAllLinks)
      return true

    si.map(_.ci).fold( { log.warn("Token is absent!"); false } ) { ci =>
      val token = ci.tokenHash
      val dsaPath = "/sys/tokens"

      if (token.isEmpty)
        log.warn("Client token is absent!")

      val fat = dslinkMgr.dsaAsk(dsaPath, GetTokens)
      val fActiveTokens = fat.asInstanceOf[Future[List[DSANode]]]

      val fExist = fActiveTokens.map {
        listNodes =>

        log.debug("Available tokens are: " + listNodes.toString)
        val tokenId = token.flatMap(a => Option(a.substring(0, 16)))

        listNodes foreach( item => log.debug("node name is: " + item.name))

        val r = listNodes.indexWhere(tokenId.isDefined && _.name.equals(tokenId.get))

        r match {
          case -1 => false
          case i: Int =>
            val tokenNode = listNodes(i)

            val check =  for {
                checkCount <- checkTokenCount(tokenId.get, tokenNode)
                checkDate <- checkTokenDates(tokenId.get, tokenNode)
              } yield checkCount && checkDate

            Await.result(check, Duration.Inf)
        }
      }

      // TODO: try to avoid Await here
      val res = Await.result(fExist, Duration.Inf)

      res
    }
  }

  def checkTokenDates(tokenId: String, tokenNode: DSANode) = {
    val dateRange = tokenNode.config("$timeRange")

    val check = dateRange map {
      range =>

      range match {
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

  private def getTransformer(sessionInfo : Option[DSLinkSessionInfo]) = {
    val format = sessionInfo.fold(MSGJSON)(si => {
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
      .toMat(Sink.asPublisher(false))(Keep.both)
      .run()(materializer)

    val sink = Sink.actorRef(toSocket, Success(()))

    val fRoutee = (registry ? GetOrCreateDSLink(sessionInfo.ci.linkName)).mapTo[Routee]

    fRoutee map { routee =>

      val (subscriptionsPusher, subscriptionsPublisher) = Source.actorRef[SubscriptionSourceMessage](bufferSize, overflow)
        .toMat(Sink.asPublisher(false))(Keep.both)
        .run()(materializer)

      routee ! SubscriptionSourceMessage(subscriptionsPusher)

      val subscriptionSrcRef = Source.fromPublisher(subscriptionsPublisher)

      val wsProps = WebSocketActor.props(toSocket, routee,
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

      subscriptionSrcRef.runWith(sink)

      val messageSink = Sink.actorRef(fromSocket, Success(()))
      val src = Source.fromPublisher(publisher)

      Flow.fromSinkAndSource[DSAMessage, DSAMessage](messageSink, src)
    }
  }

  /**
   * Extracts `dsId` from the request's query string.
   */
  private def getDsId(request: RequestHeader) = request.queryString("dsId").head

  /**
    * Extracts `salt` from the requests query string
    *
    * @param request
    * @return
    */
  private def getSalt(request: RequestHeader) = request.queryString("salt").head

  /**
    * Extracts "auth" parameter from URL
    *
    * @param request
    * @return
    */
  private def getAuth(request: RequestHeader): Option[String] = {
    if (request.queryString.contains("auth"))
      request.queryString("auth").headOption
    else
      Option(null)
  }

  /**
    * Ectracts "token" from http url
    * @param request
    * @return
    */
  private def getToken(request: RequestHeader): Option[String] = {
    if (request.queryString.contains("token"))
      request.queryString("token").headOption
    else
      Option(null)
  }

  /**
   * Constructs a connection info instance from the incoming request.
   */
  private def buildConnectionInfo(request: Request[ConnectionRequest]) = {
    val dsId = getDsId(request)
    val cr = request.body
    val availableFormats  = Settings.ServerConfiguration("format").as[Seq[String]]
    val resultFormat = chooseFormat(
      request.body.formats.getOrElse(List(MSGJSON))
      , List(availableFormats :_*)
    )
    val tempKeys = LocalKeys.generate
    val tempKey = tempKeys.encodedPublicKey
    val sharedSecret = RemoteKey.generate(tempKeys, cr.publicKey).sharedSecret

    val saltInc = Random.nextInt()
    val localSalt: String = s"${saltBase}${saltInc.toHexString}"
    val tokenHash = getToken(request)

    ConnectionInfo(dsId, dsId.substring(0, dsId.length - 44), cr.isRequester, cr.isResponder,
      cr.linkData.map(_.toString), cr.version, cr.formats.getOrElse(Nil), cr.enableWebSocketCompression,
      request.remoteAddress, request.host, resultFormat = resultFormat, tempKey = tempKey
      , sharedSecret = sharedSecret, salt = localSalt, tokenHash = tokenHash)
  }

  /**
   * Builds the authorization hash to be sent to the remote broker.
   */
  def buildAuth(tempKey: String, salt: Array[Byte]) = {
    val remoteKey = RemoteKey.generate(keys, tempKey)
    val sharedSecret = remoteKey.sharedSecret

    LocalKeys.saltSharedSecret(salt, sharedSecret)
  }
}
