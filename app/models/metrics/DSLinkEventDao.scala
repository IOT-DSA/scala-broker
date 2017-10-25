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
   * Finds connection events using the specified criteria.
   */
  def findConnectionEvents(linkName: Option[String], from: Option[DateTime],
                           to: Option[DateTime], limit: Int = 100): ListResult[ConnectionEvent]

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

  /**
   * Finds dslink session events using the specified criteria.
   */
  def findSessionEvents(linkName: Option[String], from: Option[DateTime],
                        to: Option[DateTime], limit: Int = 100): ListResult[LinkSessionEvent]
}