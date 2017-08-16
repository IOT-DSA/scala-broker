package models.metrics

import org.joda.time.DateTime

import models.akka.DSLinkMode.DSLinkMode
import models.influx.connectToInfluxDB
import models.rpc.{ RequestMessage, ResponseMessage, DSARequest }

/**
 * Logs various application metrics for subsequent retrieval and analysis.
 */
trait MetricLogger {

  /**
   * Logs a handshake attempt.
   */
  def logHandshake(ts: DateTime, linkId: String, linkName: String, linkAddress: String, mode: DSLinkMode,
                   version: String, compression: Boolean, brokerAddress: String): Unit

  /**
   * Logs a WebSocket session.
   */
  def logWebSocketSession(startTime: DateTime, endTime: DateTime, linkName: String,
                          linkAddress: String, mode: DSLinkMode, brokerAddress: String): Unit

  /**
   * Logs a RequestMessage.
   */
  def logRequestMessage(ts: DateTime, linkName: String, linkAddress: String, message: RequestMessage): Unit

  /**
   * Logs a ResponseMessage.
   */
  def logResponseMessage(ts: DateTime, linkName: String, linkAddress: String, message: ResponseMessage): Unit

  /**
   * Logs multiple requests.
   */
  def logRequests(ts: DateTime, srcLinkName: String, srcLinkAddress: String, tgtLinkName: String,
                  requests: DSARequest*): Unit
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

  def logWebSocketSession(startTime: DateTime, endTime: DateTime, linkName: String,
                          linkAddress: String, mode: DSLinkMode, brokerAddress: String) =
    logger.logWebSocketSession(startTime, endTime, linkName, linkAddress, mode, brokerAddress)

  def logRequestMessage(ts: DateTime, linkName: String, linkAddress: String, message: RequestMessage) =
    logger.logRequestMessage(ts, linkName, linkAddress, message)

  def logResponseMessage(ts: DateTime, linkName: String, linkAddress: String, message: ResponseMessage) =
    logger.logResponseMessage(ts, linkName, linkAddress, message)

  def logRequests(ts: DateTime, srcLinkName: String, srcLinkAddress: String, tgtLinkName: String,
                  requests: DSARequest*) =
    logger.logRequests(ts, srcLinkName, srcLinkAddress, tgtLinkName, requests: _*)
}