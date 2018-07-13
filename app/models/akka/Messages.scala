package models.akka

import org.joda.time.DateTime
import akka.actor.{ActorRef, Address}
import akka.stream.SourceRef
import models.akka.DSLinkMode.DSLinkMode
import models.rpc.{DSAMessage, DSAResponse}

/**
 * Common messages passed between the broker actors.
 */
object Messages {

  /**
   * Sent to Downstream actor to create or access a dslink. The Downstream actor will respond
   * with a [[Routee]] for that dslink.
   */
  case class GetOrCreateDSLink(name: String)

  /**
   * Sent to Downstream actor to remove a dslink.
   */
  case class RemoveDSLink(name: String)

  /**
   * Sent to Downstream actor to retrieve the list of dslink names. The Downstream actor will
   * respond with an `Iterable[String]` of names.
   */
  case object GetDSLinkNames

  /**
   * Sent by a dslink to the Downstream actor to register itself.
   */
  case class RegisterDSLink(name: String, mode: DSLinkMode, connected: Boolean)

  /**
   * Encapsulates DSLink state as seen by the Downstream actor.
   */
  case class LinkState(mode: DSLinkMode, connected: Boolean)

  /**
   * Sent by a dslink to the Downstream actor when its status changes.
   */
  case class DSLinkStateChanged(name: String, mode: DSLinkMode, connected: Boolean)

  /**
   * Sent by a dslink to the Downstream actor to unregister itself.
   */
  case class UnregisterDSLink(name: String)

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
                      lastConnected:    Option[DateTime],
                      lastDisconnected: Option[DateTime])

  /**
   * Sent to Downstream actor to request the aggregated [[DSLinkStats]].
   */
  case object GetDSLinkStats

  /**
   * DSLinks basic statistics for one node. Sent back by Downstream actor as part of
   * [[DSLinkStats]] response.
   */
  case class DSLinkNodeStats(address:      Address,
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
   * DSLink statistics for all nodes, sent back by Downstream actor in response to [[GetDSLinkStats]].
   */
  case class DSLinkStats(nodeStats: Map[Address, DSLinkNodeStats]) {

    private def sumUp(field: DSLinkNodeStats => Int) = nodeStats.values map field sum

    val requestersOn = sumUp(_.requestersOn)
    val requestersOff = sumUp(_.requestersOff)
    val respondersOn = sumUp(_.respondersOn)
    val respondersOff = sumUp(_.respondersOff)
    val dualsOn = sumUp(_.dualsOn)
    val dualsOff = sumUp(_.dualsOff)

    private val stats = DSLinkNodeStats(
      null,
      requestersOn, requestersOff, respondersOn, respondersOff, dualsOn, dualsOff)

    val requesters = stats.requesters
    val responders = stats.responders
    val duals = stats.duals
    val totalOn = stats.totalOn
    val totalOff = stats.totalOff
    val total = stats.total
  }

  /**
   * Sent to Downstream actor to search DSLink names matching the pattern.
   */
  case class FindDSLinks(regex: String, limit: Int, offset: Int = 0)

  /**
   * Sent to Downstream actor to remove disconnected DSLinks.
   */
  case object RemoveDisconnectedDSLinks

  case class SubscriptionNotificationMessage(msg: Int, ack: Option[Int] = None, responses: List[DSAResponse] = Nil, sid: Int, qos: QoS.Level = QoS.Default) {
    /**
      * Outputs only the first response for compact logging.
      */
    override def toString = if (responses.size < 2)
      s"ResponseMessage($msg,$ack,$responses,$sid,$qos)"
    else
      s"ResponseMessage($msg,$ack,List(${responses.head},...${responses.size - 1} more...,$sid,$qos))"
  }

  /**
    * Sent to the Tokens node and returns list of child tokens
    */
  case class GetTokens()

  /**
    * Sent to a node. The node treads as token node, updates the node configs
    * or attributes or node's value by the passed value. Configs and attributes
    * are treaded as Array
    * @param name
    * @param value
    */
  case class AppendDsId2Token(name: String, value: String)
}