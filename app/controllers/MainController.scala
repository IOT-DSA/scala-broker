package controllers

import scala.concurrent.Future

import akka.actor.{ ActorSystem, PoisonPill }
import akka.pattern.{ ask, pipe }
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import javax.inject.{ Inject, Singleton }
import models.Settings
import models.akka.DSLinkManager
import models.metrics.EventDaos
import modules.BrokerActors
import play.api.mvc.ControllerComponents

/**
 * Handles main web requests.
 */
@Singleton
class MainController @Inject() (actorSystem: ActorSystem,
                                dslinkMgr:   DSLinkManager,
                                actors:      BrokerActors,
                                eventDaos:   EventDaos,
                                cc:          ControllerComponents) extends BasicController(cc) {

  import models.akka.Messages._

  val isClusterMode = actorSystem.hasExtension(Cluster)

  val downstream = actors.downstream

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
   * Returns a future with the cluster information.
   */
  //TODO temporary, the view needs to be reworked
  private def getClusterInfo = (downstream ? GetBrokerInfo).mapTo[BrokerInfo].map {
    _.clusterInfo.getOrElse(CurrentClusterState())
  }

  /**
   * Returns a future with the number of registered dslinks by backend.
   */
  private def getDSLinkCounts = (downstream ? GetDSLinkStats).mapTo[DSLinkStats]

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
    (downstream ? FindDSLinks(regex, limit, offset)).mapTo[Iterable[String]]
}