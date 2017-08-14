package models

import scala.concurrent.ExecutionContext.Implicits.global

import org.slf4j.LoggerFactory

import com.paulgoldbaum.influxdbclient.InfluxDB

/**
 * InfluxDB helper methods.
 */
package object influx {
  import models.Settings.Influx._

  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Opens a connection to InfluxDB database.
   */
  def connectToInfluxDB: InfluxDB = {
    log.info(s"Connecting to InfluxDB at $Host:$Port")
    val dbConn = InfluxDB.connect(Host, Port)
    log.info("InfluxDB connection established")
    dbConn
  }

  /**
   * Closes an InfluxDB connection.
   */
  def closeDbConnection(dbConn: InfluxDB) = {
    log.debug(s"Closing InfluxDB connection")
    dbConn.close
    log.info(s"InfluxDB connection closed")
  }
}