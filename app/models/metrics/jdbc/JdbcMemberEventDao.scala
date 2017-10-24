package models.metrics.jdbc

import java.sql.Connection

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.joda.time.DateTime

import anorm.{ Macro, SQL, SqlStringInterpolation, sqlToSimple }
import anorm.JodaParameterMetaData.JodaDateTimeMetaData
import models.metrics.{ ListResult, MemberEvent, MemberEventDao }

/**
 * JDBC-based implementation of [[MemberEventDao]].
 */
class JdbcMemberEventDao(conn: Connection) extends JdbcGenericDao(conn) with MemberEventDao {

  private val memberEventParser = Macro.namedParser[MemberEvent]

  /**
   * Saves a member event as a record in 'member_events' table.
   */
  def saveMemberEvent(evt: MemberEvent): Unit = {
    SQL"""INSERT INTO member_events (ts, role, address, state) 
      VALUES (${evt.ts}, ${evt.role}, ${evt.address}, ${evt.state})""".executeUpdate
  }

  /**
   * Finds member_events records satisfying the criteria.
   */
  def findMemberEvents(role: Option[String], address: Option[String],
                       from: Option[DateTime], to: Option[DateTime]): ListResult[MemberEvent] = {

    val where = buildWhere(eq("role", role), eq("address", address), ge("time", from),
      le("time", to))

    Future {
      val query = "SELECT * FROM member_events" + where + " ORDER BY ts DESC"
      val result = SQL(query).executeQuery
      result.as(memberEventParser.*)
    }
  }
}