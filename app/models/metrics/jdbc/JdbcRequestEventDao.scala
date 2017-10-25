package models.metrics.jdbc

import java.sql.Connection

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.joda.time.DateTime

import anorm.{ Macro, SQL, SqlStringInterpolation, sqlToSimple }
import anorm.JodaParameterMetaData.JodaDateTimeMetaData
import models.metrics._

/**
 * JDBC-based implementation of [[RequestEventDao]].
 */
class JdbcRequestEventDao(conn: Connection) extends JdbcGenericDao(conn) with RequestEventDao {

  private val reqStatsByLinkParser = Macro.indexedParser[RequestStatsByLink]

  /**
   * Saves a request message event as a record in 'req_message' table.
   */
  def saveRequestMessageEvent(evt: RequestMessageEvent): Unit = {
    SQL"""INSERT INTO req_message (ts, inbound, link_name, link_address, msg_id, req_count)
      VALUES (${evt.ts}, ${evt.inbound}, ${evt.linkName}, ${evt.linkAddress}, 
      ${evt.msgId}, ${evt.requestCount})""".executeUpdate
  }

  /**
   * Returns request statistics.
   */
  def getRequestStats(from: Option[DateTime], to: Option[DateTime]): ListResult[RequestStatsByLink] = {
    val where = buildWhere(ge("time", from), le("time", to))

    Future {
      val query = """SELECT link_name, inbound, COUNT(msg_id) AS msg_count, 
        SUM(req_count) AS req_count FROM req_message""" + where + " GROUP BY link_name, inbound"
      val result = SQL(query).executeQuery
      result.as(reqStatsByLinkParser.*)
    }
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