package models

import scala.concurrent.ExecutionContext.Implicits.global

import org.slf4j.LoggerFactory

import com.paulgoldbaum.influxdbclient._

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

  /* converters to simplify Field creation */
  implicit def tupleToStringField(tuple: (String, String)) = StringField(tuple._1, tuple._2)
  implicit def tupleToDoubleField(tuple: (String, Double)) = DoubleField(tuple._1, tuple._2)
  implicit def tupleToLongField(tuple: (String, Long)) = LongField(tuple._1, tuple._2)
  implicit def tupleToIntField(tuple: (String, Int)) = LongField(tuple._1, tuple._2)
  implicit def tupleToBooleanField(tuple: (String, Boolean)) = BooleanField(tuple._1, tuple._2)

  /**
   * Creates a sequence of tags.
   */
  def tags(tuples: (String, String)*): Seq[Tag] = tuples map (Tag.apply _).tupled

  /**
   * Creates a sequence of fields.
   */
  def fields(flds: Field*) = Seq(flds: _*)
}