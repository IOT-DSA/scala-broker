package models.metrics

import org.joda.time.DateTime

import models.akka.DSLinkMode.DSLinkMode

/**
 * Manages DSLink event persistence.
 */
trait DSLinkEventDao {

  /**
   * Saves a dslink connection event.
   */
  def saveConnectionEvent(evt: ConnectionEvent): Unit

  /**
   * Saves a dslink connection event.
   */
  def saveConnectionEvent(ts: DateTime, event: String, sessionId: String,
                          linkId: String, linkName: String, linkAddress: String,
                          mode: DSLinkMode, version: String, compression: Boolean,
                          brokerAddress: String): Unit =
    saveConnectionEvent(ConnectionEvent(ts, event, sessionId, linkId, linkName, linkAddress,
      mode, version, compression, brokerAddress))

  /**
   * Saves a dslink session data after the session ends.
   */
  def saveSessionEvent(evt: LinkSessionEvent): Unit

  /**
   * Saves a dslink session data after the session ends.
   */
  def saveSessionEvent(startTime: DateTime, endTime: DateTime, linkName: String,
                       linkAddress: String, mode: DSLinkMode, brokerAddress: String): Unit =
    saveSessionEvent(LinkSessionEvent(startTime, endTime, linkName, linkAddress, mode, brokerAddress))
}