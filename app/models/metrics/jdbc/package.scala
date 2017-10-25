package models.metrics

import java.sql.Connection

import scala.io.Source

/**
 * JDBC helper methods.
 */
package object jdbc {

  /**
   * Creates database schema artifacts.
   */
  def createDatabaseSchema(conn: Connection) = {
    val ddl = Source.fromInputStream(getClass.getResourceAsStream("/jdbc.ddl")).mkString
    conn.createStatement.executeUpdate(ddl)
  }
}