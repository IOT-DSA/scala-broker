package models.metrics.jdbc

import java.sql.Connection

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.joda.time.DateTime

import anorm.{ Macro, SQL, SqlStringInterpolation, sqlToSimple }
import anorm.JodaParameterMetaData.JodaDateTimeMetaData
import models.metrics.{ ListResult, ResponseEventDao, ResponseMessageEvent, ResponseStatsByLink }

/**
 * JDBC-based implementation of [[ResponseEventDao]].
 */
class JdbcResponseEventDao(conn: Connection) extends JdbcGenericDao(conn) with ResponseEventDao {

  private val rspStatsByLinkParser = Macro.indexedParser[ResponseStatsByLink]

  /**
   * Saves a response message event as a record in 'rsp_message' table.
   */
  def saveResponseMessageEvent(evt: ResponseMessageEvent): Unit = {
    SQL"""INSERT INTO rsp_message (ts, inbound, link_name, link_address, msg_id, rsp_count,
      update_count, error_count) VALUES (${evt.ts}, ${evt.inbound}, ${evt.linkName}, 
      ${evt.linkAddress}, ${evt.msgId}, ${evt.responseCount}, ${evt.totalUpdates},
      ${evt.totalErrors})""".executeUpdate
  }

  /**
   * Returns response statistics by link.
   */
  def getResponseStats(from: Option[DateTime], to: Option[DateTime]): ListResult[ResponseStatsByLink] = {
    val where = buildWhere(ge("time", from), le("time", to))

    Future {
      val query = """SELECT link_name, inbound, COUNT(msg_id) AS msg_count, 
        SUM(rsp_count) AS rsp_count, SUM(update_count) AS update_count,
        SUM(error_count) AS error_count FROM rsp_message""" + where + " GROUP BY link_name, inbound"
      val result = SQL(query).executeQuery
      result.as(rspStatsByLinkParser.*)
    }
  }
}