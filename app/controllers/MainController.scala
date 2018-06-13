package controllers

import scala.concurrent.Future
import org.joda.time.DateTime
import akka.actor.ActorSystem
import akka.cluster.{Cluster, MemberStatus}
import akka.pattern.ask
import akka.routing.Routee
import javax.inject.{Inject, Singleton}

import models.Settings
import models.akka.{ BrokerActors, DSLinkManager, RichRoutee }
import play.api.mvc.ControllerComponents
import akka.actor.Address

/**
 * Handles main web requests.
 */
@Singleton
class MainController @Inject() (actorSystem: ActorSystem,
                                dslinkMgr:   DSLinkManager,
                                actors:      BrokerActors,
                                cc:          ControllerComponents) extends BasicController(cc) {

  import models.akka.Messages._

  val isClusterMode = actorSystem.hasExtension(Cluster)

  val downstream = actors.downstream
  val upstream = actors.upstream

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
    val mode = if (isClusterMode) "Clustered" else "Standalone"
    val startedAt = new DateTime(actorSystem.startTime)
    val nodes = getClusterNodes
    val dslinkStats = getDSLinkCounts
    val uplinkStats = getUplinkCounts
    for {
      down <- dslinkStats
      up <- uplinkStats
    } yield Ok(views.html.cluster(mode, startedAt, nodes.map(ni => (ni.address -> ni)).toMap, down, up))
  }

  /**
   * Displays data explorer.
   */
  def dataExplorer = Action.async { implicit request =>
    getDownUpCount map {
      case (down, up) => Ok(views.html.data(Some(down), Some(up)))
    }
  }

  /**
   * Displays the DSLinks page.
   */
  def findDslinks(regex: String, limit: Int, offset: Int) = Action.async {
    val fLinkNames = getDSLinkNames(regex, limit, offset)
    val fDownUpCount = getDownUpCount
    for {
      linksByAddress <- fLinkNames
      (down, up) <- fDownUpCount
      listOfFutures = linksByAddress map {
        case (address, names) => 
          val links = names.map(getDSLinkRoutee)
          val fInfos = Future.sequence(links.map(fLink => fLink.flatMap(link => (link ? GetLinkInfo).mapTo[LinkInfo])))
          fInfos.map(infos => address -> infos)
      }
      infos <- Future.sequence(listOfFutures).map(_.toMap)
      } yield Ok(views.html.links(regex, limit, offset, infos, down, Some(down), Some(up)))
  }

  /**
   * Displays upstream connections.
   */
  def uplinks = Action.async {
    val fLinkNames = getUplinkNames
    val fDownUpCount = getDownUpCount
    for {
      names <- fLinkNames
      (down, up) <- fDownUpCount
      links <- Future.sequence(names map getUplinkRoutee)
      infos <- Future.sequence(links map (link => (link ? GetLinkInfo).mapTo[LinkInfo]))
    } yield Ok(views.html.upstream(infos, down, Some(down), Some(up)))
  }

  /**
   * Disconnects the dslink from endpoint.
   */
  def disconnectEndpoint(name: String) = Action.async {
    getDSLinkRoutee(name) map { routee =>
      routee ! DisconnectEndpoint(true)
      Ok(s"Endpoint '$name' disconnected")
    }
  }

  /**
   * Removes the DSLink.
   */
  def removeLink(name: String) = Action {
    downstream ! RemoveDSLink(name)
    Ok(s"DSLink '$name' removed")
  }
  
  /**
   * Removes disconnected dslinks.
   */
  def removeDisconnectedLinks() = Action {
    downstream ! RemoveDisconnectedDSLinks
    Ok("All disconnected dslinks removed")
  }

  /**
   * Displays the configuration.
   */
  def viewConfig = Action.async {
    getDownUpCount map {
      case (down, up) => Ok(views.html.config(Settings.rootConfig.root, Some(down), Some(up)))
    }
  }

  /**
   * Returns a future with the cluster node information.
   */
  private def getClusterNodes = if (isClusterMode) {
    val state = Cluster(actorSystem).state
    state.members map { m =>
      val status = if (state.unreachable contains m) "Unreachable" else m.status.toString
      NodeInfo(m.address, state.leader == Some(m.address), m.uniqueAddress.longUid, m.roles, status)
    }
  } else Set(NodeInfo(downstream.path.address, true, -1, Set.empty, MemberStatus.Up.toString))

  /**
   * Returns a future with the number of registered dslinks by backend.
   */
  private def getDSLinkCounts = (downstream ? GetDSLinkStats).mapTo[DSLinkStats]

  /**
   * Returns a future with the number of registered uplinks by backend.
   */
  private def getUplinkCounts = (upstream ? GetDSLinkStats).mapTo[DSLinkStats]

  /**
   * Returns a future with the total number of registered dslinks.
   */
  private def getTotalDSLinkCount = getDSLinkCounts map (_.total)

  /**
   * Returns a future with the total number of registered uplinks.
   */
  private def getTotalUplinkCount = getUplinkCounts map (_.total)

  /**
   * Combines `getTotalDSLinkCount` and `getTotalUplinkCount` to produce a future tuple (down, up).
   */
  private def getDownUpCount = {
    val fDown = getTotalDSLinkCount
    val fUp = getTotalUplinkCount
    for (down <- fDown; up <- fUp) yield (down, up)
  }

  /**
   * Returns a future with the list of dslink names matching the criteria.
   */
  private def getDSLinkNames(regex: String, limit: Int, offset: Int) =
    (downstream ? FindDSLinks(regex, limit, offset)).mapTo[Map[Address, Iterable[String]]]

  /**
   * Returns a future with the list of uplink names.
   */
  private def getUplinkNames() = (upstream ? GetDSLinkNames).mapTo[Iterable[String]]

  /**
   * Returns a future Routee for a given dslink.
   */
  private def getDSLinkRoutee(name: String) = (downstream ? GetOrCreateDSLink(name)).mapTo[Routee]

  /**
   * Returns a future Routee for a given uplink.
   */
  private def getUplinkRoutee(name: String) = (upstream ? GetOrCreateDSLink(name)).mapTo[Routee]
}