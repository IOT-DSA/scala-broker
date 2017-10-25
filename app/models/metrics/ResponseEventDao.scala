package models.metrics

import org.joda.time.DateTime

import models.rpc.ResponseMessage

/**
 * Manages DSLink response event persistence.
 */
trait ResponseEventDao {

  /**
   * Saves a response message event.
   */
  def saveResponseMessageEvent(evt: ResponseMessageEvent): Unit

  /**
   * Saves a response message event.
   */
  def saveResponseMessageEvent(ts: DateTime, inbound: Boolean, linkName: String,
                               linkAddress: String, msgId: Int, responseCount: Int,
                               totalUpdates: Int, totalErrors: Int): Unit =
    saveResponseMessageEvent(ResponseMessageEvent(ts, inbound, linkName, linkAddress,
      msgId, responseCount, totalUpdates, totalErrors))

  /**
   * Saves a response message event.
   */
  def saveResponseMessageEvent(ts: DateTime, inbound: Boolean, linkName: String,
                               linkAddress: String, message: ResponseMessage): Unit =
    saveResponseMessageEvent(ts, inbound, linkName, linkAddress,
      message.msg, message.responses.size,
      message.responses.map(_.updates.map(_.size).getOrElse(0)).sum,
      message.responses.filter(_.error.isDefined).size)

  /**
   * Returns response statistics by link.
   */
  def getResponseStats(from: Option[DateTime], to: Option[DateTime]): ListResult[ResponseStatsByLink]
}