package models.metrics.jdbc

import java.sql.Connection

import models.metrics.{ RequestBatchEvent, RequestEventDao, RequestMessageEvent }

/**
 * JDBC-based implementation of [[RequestEventDao]].
 */
class JdbcRequestEventDao(conn: Connection) extends RequestEventDao {

  def saveRequestMessageEvent(evt: RequestMessageEvent): Unit = {}

  def saveRequestBatchEvent(evt: RequestBatchEvent): Unit = {}
}