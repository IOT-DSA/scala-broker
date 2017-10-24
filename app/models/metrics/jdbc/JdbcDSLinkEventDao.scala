package models.metrics.jdbc

import java.sql.Connection

import anorm.JodaParameterMetaData.JodaDateTimeMetaData
import anorm.SqlStringInterpolation
import models.metrics.{ ConnectionEvent, DSLinkEventDao, LinkSessionEvent }

/**
 * JDBC-based implementation of [[DSLinkEventDao]].
 */
class JdbcDSLinkEventDao(conn: Connection) extends DSLinkEventDao {

  implicit private val connection = conn

  /**
   * Saves a connection event as a record in 'link_conn' measurement.
   */
  def saveConnectionEvent(evt: ConnectionEvent): Unit = {
    SQL"""INSERT INTO link_conn (ts, event, session_id, link_id, link_name, link_address, 
      mode, version, compression, broker_address) VALUES (${evt.ts}, ${evt.event}, 
      ${evt.sessionId}, ${evt.linkId}, ${evt.linkName}, ${evt.linkAddress}, ${evt.mode.toString}, 
      ${evt.version}, ${evt.compression}, ${evt.brokerAddress})""".executeUpdate
  }

  /**
   * Saves a session event as a record in 'link_session' measurement.
   */
  def saveSessionEvent(evt: LinkSessionEvent): Unit = {
    SQL"""INSERT INTO link_session (start_ts, end_ts, link_name, link_address, mode, broker_address)
      VALUES (${evt.startTime}, ${evt.endTime}, ${evt.linkName}, ${evt.linkAddress},
      ${evt.mode.toString}, ${evt.brokerAddress})""".executeUpdate
  }
}