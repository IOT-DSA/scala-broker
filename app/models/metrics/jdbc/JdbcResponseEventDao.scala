package models.metrics.jdbc

import java.sql.Connection

import models.metrics.{ ResponseEventDao, ResponseMessageEvent }

/**
 * JDBC-based implementation of [[ResponseEventDao]].
 */
class JdbcResponseEventDao(conn: Connection) extends ResponseEventDao {
  def saveResponseMessageEvent(evt: ResponseMessageEvent): Unit = {}
}