package models.metrics.jdbc

import java.sql.Connection

import models.metrics.{ RequestBatchEvent, RequestEventDao, RequestMessageEvent }
import anorm.{ Macro, SQL, SqlStringInterpolation, sqlToSimple }
import anorm.JodaParameterMetaData.JodaDateTimeMetaData

/**
 * JDBC-based implementation of [[RequestEventDao]].
 */
class JdbcRequestEventDao(conn: Connection) extends JdbcGenericDao(conn) with RequestEventDao {

  /**
   * Saves a request message event as a record in 'req_message' table.
   */
  def saveRequestMessageEvent(evt: RequestMessageEvent): Unit = {
    SQL"""INSERT INTO req_message (ts, inbound, link_name, link_address, msg_id, req_count)
      VALUES (${evt.ts}, ${evt.inbound}, ${evt.linkName}, ${evt.linkAddress}, 
      ${evt.msgId}, ${evt.requestCount})""".executeUpdate
  }

  /**
   * Saves a request batch event as a record in 'req_batch' table.
   */
  def saveRequestBatchEvent(evt: RequestBatchEvent): Unit = {
    SQL"""INSERT INTO req_batch (ts, src_link_name, src_link_address, tgt_link_name,
      method, size) VALUES (${evt.ts}, ${evt.srcLinkName}, ${evt.srcLinkAddress},
      ${evt.tgtLinkName}, ${evt.method.toString}, ${evt.size})""".executeUpdate
  }
}