package controllers

import scala.concurrent.Future

import akka.actor._
import akka.pattern.{ ask, pipe }
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.stream.Materializer
import akka.util.Timeout
import akka.stream.scaladsl._
import javax.inject.{ Inject, Singleton }
import models.Settings
import models.akka.ConnectionInfo
import models.akka.cluster.FrontendActor
import models.akka.cluster.DSLinkActor
import models.akka.cluster.DSLinkActor.{ DSLinkEnvelope, GetLinkInfo, LinkInfo }
import models.rpc.{ DSAMessage, DSAMessageFormat }
import play.api.Logger
import play.api.cache.CacheApi
import play.api.inject.ApplicationLifecycle
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsError, JsValue, Json, Reads }
import play.api.mvc.{ Action, BodyParsers, Controller, Request, RequestHeader, WebSocket }
import play.api.mvc.WebSocket.MessageFlowTransformer.jsonMessageFlowTransformer
import akka.actor.PoisonPill
import models.akka.cluster.DSLinkActor.DisconnectEndpoint
import models.akka.cluster.WSActor
import models.akka.cluster.WSActorConfig
import akka.stream.OverflowStrategy
import models.akka.cluster.ShardedDSLinkProxy

/**
 * Handles main web requests.
 */
@Singleton
class MainController @Inject() (implicit actorSystem: ActorSystem,
                                materializer: Materializer, cache: CacheApi,
                                life: ApplicationLifecycle) extends Controller {
  import models.akka.cluster.BackendActor._
  import models.akka.cluster.FrontendActor._

  private val log = Logger(getClass)

  implicit private val timeout = Timeout(Settings.QueryTimeout)

  private val transformer = jsonMessageFlowTransformer[DSAMessage, DSAMessage]

  DSLinkActor.proxyStart(actorSystem)

  private val frontend = actorSystem.actorOf(FrontendActor.props, "frontend")

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
      val countsByAddress = lc.map {
        case (ref, count) => ref.path.address -> count
      }
      Ok(views.html.cluster(info, countsByAddress, Some(lc.values.sum), Some(upCount)))
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
      infos <- Future.sequence(names map getDSLinkInfo)
    } yield Ok(views.html.links(regex, limit, offset, infos, down, Some(down), Some(up)))
  }

  /**
   * Disconnects the dslink from Web Socket.
   */
  def disconnectWS(name: String) = Action {
    new ShardedDSLinkProxy(name) tell DisconnectEndpoint(true)
    Ok(s"Endpoint '$name' disconnected")
  }

  /**
   * Removes the DSLink.
   */
  def removeLink(name: String) = Action {
    new ShardedDSLinkProxy(name) tell PoisonPill
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

    cache.set(ci.dsId, ci)

    log.debug(s"Conn response sent: ${json.toString}")
    Ok(json)
  }

  /**
   * Establishes a WebSocket connection.
   */
  def ws = WebSocket.acceptOrResult[DSAMessage, DSAMessage] { request =>
    log.debug(s"WS request received: $request")
    val dsId = getDsId(request)
    val connInfo = cache.get[ConnectionInfo](dsId)
    log.debug(s"Conn info retrieved for $dsId: $connInfo")

    Future.successful(connInfo.map(createWSFlow(_)).toRight(Forbidden))
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
   * Creates a new WebSocket flow bound to a newly created WSActor.
   */
  private def createWSFlow(ci: ConnectionInfo,
                           bufferSize: Int = 16, overflow: OverflowStrategy = OverflowStrategy.dropNew) = {
    import akka.actor.Status._

    val (toSocket, publisher) = Source.actorRef[DSAMessage](bufferSize, overflow)
      .toMat(Sink.asPublisher(false))(Keep.both).run()(materializer)

    val proxy = new ShardedDSLinkProxy(ci.linkName)
    val wsProps = WSActor.props(toSocket, proxy, WSActorConfig(ci, Settings.Salt))

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
    ConnectionInfo(getDsId(request), request.body)

  /**
   * Returns a future with the cluster information.
   */
  private def getClusterInfo = (frontend ? GetClusterInfo).mapTo[CurrentClusterState]

  /**
   * Returns a future with the number of registered dslinks by backend.
   */
  private def getDSLinkCounts = (frontend ? GetDSLinkCount).mapTo[Map[ActorRef, Int]]

  /**
   * Returns a future with the total number of registered dslinks.
   */
  private def getTotalDSLinkCount = getDSLinkCounts map (_.values.sum)

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

  /**
   * Returns a future with the dslink info.
   */
  private def getDSLinkInfo(linkName: String) = new ShardedDSLinkProxy(linkName).ask[LinkInfo](GetLinkInfo)
}