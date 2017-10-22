package models.metrics

import org.joda.time.DateTime

import models.rpc.{ DSARequest, RequestMessage }
import models.rpc.DSAMethod.DSAMethod

/**
 * Manages DSLink request event persistence.
 */
trait RequestEventDao {

  /**
   * Saves a request message event.
   */
  def saveRequestMessageEvent(evt: RequestMessageEvent): Unit

  /**
   * Saves a request message event.
   */
  def saveRequestMessageEvent(ts: DateTime, inbound: Boolean, linkName: String,
                              linkAddress: String, msgId: Int, requestCount: Int): Unit =
    saveRequestMessageEvent(RequestMessageEvent(ts, inbound, linkName, linkAddress,
      msgId, requestCount))

  /**
   * Saves a request message event.
   */
  def saveRequestMessageEvent(ts: DateTime, inbound: Boolean, linkName: String,
                              linkAddress: String, message: RequestMessage): Unit =
    saveRequestMessageEvent(ts, inbound, linkName, linkAddress, message.msg, message.requests.size)

  /**
   * Saves a request batch event.
   */
  def saveRequestBatchEvent(evt: RequestBatchEvent): Unit

  /**
   * Saves a request batch event.
   */
  def saveRequestBatchEvent(ts: DateTime, srcLinkName: String, srcLinkAddress: String,
                            tgtLinkName: String, method: DSAMethod, size: Int): Unit =
    saveRequestBatchEvent(RequestBatchEvent(ts, srcLinkName, srcLinkAddress, tgtLinkName,
      method, size))

  /**
   * Saves a set of request batch event.
   */
  def saveRequestBatchEvents(ts: DateTime, srcLinkName: String, srcLinkAddress: String,
                             tgtLinkName: String, requests: DSARequest*): Unit = {
    val countsByMethod = requests.groupBy(_.method).mapValues(_.size)
    countsByMethod foreach {
      case (method, size) =>
        saveRequestBatchEvent(ts, srcLinkName, srcLinkAddress, tgtLinkName, method, size)
    }
  }
}