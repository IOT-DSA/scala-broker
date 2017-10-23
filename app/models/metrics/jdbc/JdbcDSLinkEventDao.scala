package models.metrics.jdbc

import java.sql.Connection

import models.metrics.{ ConnectionEvent, DSLinkEventDao, LinkSessionEvent }

/**
 * JDBC-based implementation of [[DSLinkEventDao]].
 */
class JdbcDSLinkEventDao(conn: Connection) extends DSLinkEventDao {

  def saveConnectionEvent(evt: ConnectionEvent): Unit = {}

  def saveSessionEvent(evt: LinkSessionEvent): Unit = {}
}