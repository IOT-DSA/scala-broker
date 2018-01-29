package models.metrics.jdbc

import java.sql.Connection

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.joda.time.DateTime

import anorm.{ Macro, SQL, SqlStringInterpolation, sqlToSimple }
import anorm.JodaParameterMetaData.JodaDateTimeMetaData
import javax.inject.Inject
import models.akka.DSLinkMode
import models.metrics.{ ConnectionEvent, DSLinkEventDao, LinkSessionEvent, ListResult }

/**
 * JDBC-based implementation of [[DSLinkEventDao]].
 */
class JdbcDSLinkEventDao @Inject() (conn: Connection) extends JdbcGenericDao(conn) with DSLinkEventDao {

  implicit val dslinkModeExtractor = EnumColumn.forEnum(DSLinkMode)

  private val connEventParser = Macro.indexedParser[ConnectionEvent]

  private val sessionEventParser = Macro.indexedParser[LinkSessionEvent]

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
   * Finds link_conn records satisfying the criteria.
   */
  def findConnectionEvents(linkName: Option[String], from: Option[DateTime],
                           to: Option[DateTime], limit: Int): ListResult[ConnectionEvent] = {

    val where = buildWhere(eq("link_name", linkName), ge("time", from), le("time", to))

    Future {
      val query = "SELECT * FROM link_conn" + where + " ORDER BY ts DESC LIMIT " + limit
      val result = SQL(query).executeQuery
      result.as(connEventParser.*)
    }
  }

  /**
   * Saves a session event as a record in 'link_session' measurement.
   */
  def saveSessionEvent(evt: LinkSessionEvent): Unit = {
    SQL"""INSERT INTO link_session (session_id, start_ts, end_ts, link_name, link_address, mode, broker_address)
      VALUES (${evt.sessionId}, ${evt.startTime}, ${evt.endTime}, ${evt.linkName}, ${evt.linkAddress},
      ${evt.mode.toString}, ${evt.brokerAddress})""".executeUpdate
  }

  /**
   * Finds link_session records satisfying the criteria.
   */
  def findSessionEvents(linkName: Option[String], from: Option[DateTime],
                        to: Option[DateTime], limit: Int): ListResult[LinkSessionEvent] = {

    val where = buildWhere(eq("link_name", linkName), ge("time", from), le("time", to))

    Future {
      val query = "SELECT * FROM link_session" + where + " ORDER BY start_ts DESC LIMIT " + limit
      val result = SQL(query).executeQuery
      result.as(sessionEventParser.*)
    }
  }
}