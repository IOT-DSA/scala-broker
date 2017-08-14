package models.metrics

import org.joda.time.DateTime

import models.akka.DSLinkMode.DSLinkMode
import models.influx.connectToInfluxDB

/**
 * Logs various application metrics for subsequent retrieval and analysis.
 */
trait MetricLogger {

  /**
   * Logs a handshake attempt.
   */
  def logHandshake(ts: DateTime,
                   linkId: String, linkName: String, linkAddress: String, mode: DSLinkMode,
                   version: String, compression: Boolean, brokerAddress: String): Unit
}

/**
 * Factory for [[MetricLogger]], uses application config to instantiate the appropriate logger.
 */
object MetricLogger extends MetricLogger {
  import models.Settings.Influx._
  import models.Settings.Metrics._

  private val dbConn = if (Collect) Some(connectToInfluxDB) else None
  private val db = dbConn map (_.selectDatabase(DbName))

  private val logger = db map (new InfluxMetricLogger(_)) getOrElse (new NullMetricLogger)

  sys.addShutdownHook { dbConn foreach (_.close) }

  def logHandshake(ts: DateTime, linkId: String, linkName: String, linkAddress: String, mode: DSLinkMode,
                   version: String, compression: Boolean, brokerAddress: String) =
    logger.logHandshake(ts, linkId, linkName, linkAddress, mode, version, compression, brokerAddress)
}