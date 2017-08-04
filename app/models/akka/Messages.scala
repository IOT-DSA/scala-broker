package models.akka

import org.joda.time.DateTime

import akka.actor.{ ActorPath, ActorRef, Address }
import akka.cluster.ClusterEvent

/**
 * Common messages passed between the broker actors.
 */
object Messages {

  /**
   * Sent by facade to a DSLinkActor to connect it to the specified Endpoint actor (the
   * one that manages the physical connection to a remote process via WebSockets, TCP/IP, etc.
   */
  case class ConnectEndpoint(ep: ActorRef, ci: ConnectionInfo)

  /**
   * Sent by facade to a DSLinkActor to disconnect it from  the endpoint actor,
   * optionally sending a PoisonPill to the endpoint.
   */
  case class DisconnectEndpoint(killEndpoint: Boolean)

  /**
   * Sent to a DSLinkActor to request detailed link information.
   */
  case object GetLinkInfo

  /**
   * Sent by DSLinkActor back to the caller in response to [[GetLinkInfo]] message.
   */
  case class LinkInfo(ci: ConnectionInfo, connected: Boolean,
                      lastConnected: Option[DateTime],
                      lastDisconnected: Option[DateTime])

  /**
   * Sent to FrontendActor to request the broker information.
   */
  case object GetBrokerInfo

  /**
   * Broker information, sent back by FrontendActor in response to [[GetBrokerInfo]] message.
   */
  case class BrokerInfo(backends: Seq[ActorPath], clusterInfo: Option[ClusterEvent.CurrentClusterState])

  /**
   * Sent to FrontendActor to request the aggregated [[DSLinkStats]] or sent to BackendActor
   * to request [[DSLinkNodeStats]].
   */
  case object GetDSLinkStats

  /**
   * DSLinks basic statistics for one node. Sent back by BackendActor in response to [[GetDSLinkStats]]
   * or as part of FrontendActor's response [[DSLinkStats]].
   */
  case class DSLinkNodeStats(address: Address,
                             requestersOn: Int, requestersOff: Int,
                             respondersOn: Int, respondersOff: Int,
                             dualsOn: Int, dualsOff: Int) {
    val requesters = requestersOn + requestersOff
    val responders = respondersOn + respondersOff
    val duals = dualsOn + dualsOff
    val totalOn = requestersOn + respondersOn + dualsOn
    val totalOff = requestersOff + respondersOff + dualsOff
    val total = totalOn + totalOff
  }

  /**
   * DSLink statistics for all nodes, sent back by FrontendActor in response to [[GetDSLinkStats]].
   */
  case class DSLinkStats(nodeStats: Map[Address, DSLinkNodeStats]) {

    private def sumUp(field: DSLinkNodeStats => Int) = nodeStats.values map field sum

    val requestersOn = sumUp(_.requestersOn)
    val requestersOff = sumUp(_.requestersOff)
    val respondersOn = sumUp(_.respondersOn)
    val respondersOff = sumUp(_.respondersOff)
    val dualsOn = sumUp(_.dualsOn)
    val dualsOff = sumUp(_.dualsOff)

    private val stats = DSLinkNodeStats(null,
      requestersOn, requestersOff, respondersOn, respondersOff, dualsOn, dualsOff)

    val requesters = stats.requesters
    val responders = stats.responders
    val duals = stats.duals
    val totalOn = stats.totalOn
    val totalOff = stats.totalOff
    val total = stats.total
  }

  /**
   * Sent to FrontendActor or BackendActor to search DSLink names matching the pattern.
   */
  case class FindDSLinks(regex: String, limit: Int, offset: Int = 0)

  /**
   * Sent to FrontendActor or BackendActor to remove disconnected DSLinks.
   */
  case object RemoveDisconnectedDSLinks
}