package controllers

import scala.concurrent.Future
import scala.util.Random

import org.joda.time.DateTime

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.pattern.ask
import akka.stream.{ Materializer, OverflowStrategy }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.util.Timeout
import javax.inject.{ Inject, Singleton }
import models.Settings
import models.akka._
import models.akka.cluster.ClusteredDSLinkManager
import models.akka.local.{ DownstreamActor, LocalDSLinkManager }
import models.metrics.MetricDao.dslinkEventDao
import models.rpc.{ DSAMessage, DSAMessageFormat }
import play.api.Logger
import play.api.cache.SyncCacheApi
import play.api.libs.json.{ JsError, Json, Reads }
import play.api.mvc.{ AbstractController, ControllerComponents, Request, RequestHeader, WebSocket }
import play.api.mvc.WebSocket.MessageFlowTransformer.jsonMessageFlowTransformer

/**
 * Handles main web requests.
 */
@Singleton
class MainController @Inject() (implicit actorSystem: ActorSystem,
                                materializer: Materializer, cache: SyncCacheApi,
                                cc: ControllerComponents) extends AbstractController(cc) {
  import models.akka.Messages._

  private val log = Logger(getClass)

  implicit private val timeout = Timeout(Settings.QueryTimeout)

  implicit private val executionContext = cc.executionContext

  implicit val ConnectionRequestReads = Json.reads[ConnectionRequest]

  private val transformer = jsonMessageFlowTransformer[DSAMessage, DSAMessage]

  val isClusterMode = actorSystem.hasExtension(Cluster)

  private val frontend = actorSystem.actorOf(FrontendActor.props, "frontend")

  val dslinkMgr = if (isClusterMode)
    new ClusteredDSLinkManager(true)
  else
    new LocalDSLinkManager

  if (!isClusterMode) {
    actorSystem.actorOf(BackendActor.props(dslinkMgr), "backend")
    actorSystem.actorOf(DownstreamActor.props, "downstream")
    actorSystem.actorOf(RootNodeActor.props, Settings.Nodes.Root)
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
   * Displays the cluster information.
   */
  def clusterInfo = Action.async {
    val clusterInfo = getClusterInfo
    val linkCounts = getDSLinkCounts
    for (info <- clusterInfo; lc <- linkCounts; upCount <- getUpstreamCount) yield {
      val countsByAddress = lc.nodeStats.map {
        case (address, stats) => address -> stats.total
      }
      Ok(views.html.cluster(info, countsByAddress, Some(countsByAddress.values.sum), Some(upCount)))
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
    val fLinkNames = getDSLinkNames(regex, limit, offset)
    val fDownUpCount = getDownUpCount
    for {
      names <- fLinkNames
      (down, up) <- fDownUpCount
      infos <- Future.sequence(names map dslinkMgr.getDSLinkInfo)
    } yield Ok(views.html.links(regex, limit, offset, infos, down, Some(down), Some(up)))
  }

  /**
   * Disconnects the dslink from Web Socket.
   */
  def disconnectWS(name: String) = Action {
    dslinkMgr.disconnectEndpoint(name, true)
    Ok(s"Endpoint '$name' disconnected")
  }

  /**
   * Removes the DSLink.
   */
  def removeLink(name: String) = Action {
    dslinkMgr.tellDSLink(name, PoisonPill)
    Ok(s"DSLink '$name' removed")
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
   * Validates the JSON and extracts a request message.
   */
  private def validateJson[A: Reads] = parse.tolerantJson.validate { js =>
    js.validate[A].asEither.left.map { e =>
      log.error(s"Cannot parse connection request JSON: $js. Error info: ${JsError.toJson(e)}")
      BadRequest(JsError.toJson(e))
    }
  }

  /**
   * Creates a new WebSocket flow bound to a newly created WSActor.
   */
  private def createWSFlow(
    sessionInfo: DSLinkSessionInfo,
    bufferSize:  Int               = 16, overflow: OverflowStrategy = OverflowStrategy.dropNew) = {
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
  private def buildConnectionInfo(request: Request[ConnectionRequest]) =
    ConnectionInfo(getDsId(request), request.body, request.remoteAddress, request.host)

  /**
   * Returns a future with the cluster information.
   */
  //TODO temporary, the view needs to be reworked
  private def getClusterInfo = (frontend ? GetBrokerInfo).mapTo[BrokerInfo].map {
    _.clusterInfo.getOrElse(CurrentClusterState())
  }

  /**
   * Returns a future with the number of registered dslinks by backend.
   */
  private def getDSLinkCounts = (frontend ? GetDSLinkStats).mapTo[DSLinkStats]

  /**
   * Returns a future with the total number of registered dslinks.
   */
  private def getTotalDSLinkCount = getDSLinkCounts map (_.total)

  /**
   * Returns a future with the number of registered upstream connections (currently always 0).
   */
  private def getUpstreamCount = Future.successful(0)

  /**
   * Combines `getTotalDSLinkCount` and `getUpstreamCount` to produce a future tuple (down, up).
   */
  private def getDownUpCount = {
    val fDown = getTotalDSLinkCount
    val fUp = getUpstreamCount
    for (down <- fDown; up <- fUp) yield (down, up)
  }

  /**
   * Returns a future with the list of dslink names matching the criteria.
   */
  private def getDSLinkNames(regex: String, limit: Int, offset: Int) =
    (frontend ? FindDSLinks(regex, limit, offset)).mapTo[Iterable[String]]
}