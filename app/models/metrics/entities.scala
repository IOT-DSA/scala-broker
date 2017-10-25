package models.metrics

import org.joda.time.DateTime

import models.akka.DSLinkMode.DSLinkMode
import models.rpc.DSAMethod.DSAMethod

/**
 * Base trait for broker events.
 */
trait Event {
  val ts: DateTime
}

/**
 * An event associated with the broker cluster node.
 */
case class MemberEvent(ts: DateTime, role: String, address: String, state: String) extends Event

/**
 * A DSLink connection event.
 */
case class ConnectionEvent(ts: DateTime, event: String, sessionId: String,
                           linkId: String, linkName: String, linkAddress: String,
                           mode: DSLinkMode, version: String, compression: Boolean,
                           brokerAddress: String) extends Event

/**
 * A DSLink session event.
 */
case class LinkSessionEvent(startTime: DateTime, endTime: DateTime, linkName: String,
                            linkAddress: String, mode: DSLinkMode, brokerAddress: String) extends Event {
  val ts = startTime
}

/**
 * Generated when a request message is received from a requester DSLink (`inbound == true`),
 * or when a request message is sent to a responder dslink (`inbound == false`).
 */
case class RequestMessageEvent(ts: DateTime, inbound: Boolean, linkName: String,
                               linkAddress: String, msgId: Int, requestCount: Int) extends Event

/**
 * Generated when a request batch from one dslink is sent to another dslink.
 */
case class RequestBatchEvent(ts: DateTime, srcLinkName: String, srcLinkAddress: String,
                             tgtLinkName: String, method: DSAMethod, size: Int) extends Event

/**
 * Generated when a response message is received from a responder DSLink (`inbound == true`),
 * or when a response message is sent to a requester dslink (`inbound == false`).
 */
case class ResponseMessageEvent(ts: DateTime, inbound: Boolean, linkName: String,
                                linkAddress: String, msgId: Int, responseCount: Int,
                                totalUpdates: Int, totalErrors: Int) extends Event {
  val hasResponses = responseCount > 0
  val hasUpdates = totalUpdates > 0
  val hasErrors = totalErrors > 0
}

/**
 * Request statistics by link.
 */
case class RequestStatsByLink(linkName: String, inbound: Boolean, msgCount: Int, reqCount: Int)

/**
 * Request statistics for the source and target links.
 */
case class RequestStatsByMethod(srcLinkName: String, tgtLinkName: String, counts: Map[String, Int])

/**
 * Response statistics by link.
 */
case class ResponseStatsByLink(linkName: String, inbound: Boolean, msgCount: Int, rspCount: Int,
                               updateCount: Int, errorCount: Int)