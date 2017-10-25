package models.metrics.jdbc

import java.sql.Connection
import play.api.Logger
import org.joda.time.DateTime

/**
 * Base class for JDBC-based DAOs, contains helper methods for subclasses.
 */
abstract class JdbcGenericDao(conn: Connection) {

  protected val log = Logger(getClass)

  implicit protected val connection = conn

  /**
   * Builds a WHERE clause using the defined filters.
   */
  protected def buildWhere(filters: Option[String]*) = {
    val validFilters = filters collect { case Some(x) => x }
    if (!validFilters.isEmpty)
      filters.mkString(" WHERE ", " AND ", "")
    else
      ""
  }

  /**
   * Bulds a clause that compares a column with a string value for equality.
   */
  protected def eq(colName: String, str: Option[String]) = str map (x => s"$colName = '$x'")

  /**
   * Bulds a clause that compares a column with a date value for less-than-or-equal.
   */
  protected def le(colName: String, ts: Option[DateTime]) = ts map (_.getMillis) map (x => s"$colName <= $x")

  /**
   * Bulds a clause that compares a column with a date value for greater-than-or-equal.
   */
  protected def ge(colName: String, ts: Option[DateTime]) = ts map (_.getMillis) map (x => s"$colName >= $x")
}