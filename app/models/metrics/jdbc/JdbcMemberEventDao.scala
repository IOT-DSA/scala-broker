package models.metrics.jdbc

import java.sql.Connection

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.joda.time.DateTime

import anorm.{ Macro, SQL, SqlStringInterpolation, sqlToSimple }
import anorm.JodaParameterMetaData.JodaDateTimeMetaData
import models.metrics.{ MemberEvent, MemberEventDao }

/**
 * JDBC-based implementation of [[MemberEventDao]].
 */
class JdbcMemberEventDao(conn: Connection) extends MemberEventDao {

  implicit private val connection = conn

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
                       from: Option[DateTime], to: Option[DateTime]): Future[List[MemberEvent]] = {
    val pRole = role map (x => s"role = '$x'")
    val pAddress = address map (x => s"address = '$x'")
    val pFrom = from map (_.getMillis) map (x => s"time >= $x")
    val pTo = to map (_.getMillis) map (x => s"time <= $x")

    val filters = List(pRole, pAddress, pFrom, pTo) collect { case Some(f) => f }
    val where = if (!filters.isEmpty)
      filters.mkString(" WHERE ", " AND ", "")
    else
      ""

    Future {
      val query = "SELECT * FROM member_events" + where + " ORDER BY ts DESC"
      val result = SQL(query).executeQuery
      result.as(memberEventParser.*)
    }
  }
}