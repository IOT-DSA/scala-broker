package models.metrics.influxdb

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.slf4j.LoggerFactory

import com.paulgoldbaum.influxdbclient.{ Database, InfluxDB }

import models.Settings.InfluxDb._

/**
 * Creates and initializes InfluxDB database.
 */
object InfluxDbSetup extends App {

  private val log = LoggerFactory.getLogger(getClass)

  val dbConn = connectToInfluxDB(Host, Port)

  val fdb = createDatabase(dbConn, DbName)

  try {
    fdb foreach createRetentionPolicies
    Thread.sleep(1000)
  } finally {
    closeDbConnection(dbConn)
  }

  /**
   * Creates a database with the given name.
   */
  private def createDatabase(dbConn: InfluxDB, name: String): Future[Database] = {
    val db = dbConn.selectDatabase(DbName)
    db.exists flatMap {
      case true  => db.drop
      case false => Future.successful({})
    } flatMap (_ => db.create) map { _ =>
      log.info(s"Database ${db.databaseName} created")
      db
    }
  }

  /**
   * Creates common retention policies in the database.
   */
  private def createRetentionPolicies(db: Database) = for {
    day <- db.createRetentionPolicy("day", "1d", 1, false)
    week <- db.createRetentionPolicy("week", "1w", 1, false)
    month <- db.createRetentionPolicy("month", "30d", 1, false)
    quarter <- db.createRetentionPolicy("quarter", "13w", 1, false)
    year <- db.createRetentionPolicy("year", "52w", 1, false)
  } yield {
    log.info(s"Retention policies created")
    db
  }
}