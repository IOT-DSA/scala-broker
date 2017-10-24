package models.metrics.influxdb

import scala.concurrent.ExecutionContext.Implicits.global

import org.joda.time.{ DateTime, Interval }

import com.paulgoldbaum.influxdbclient._
import com.paulgoldbaum.influxdbclient.Parameter.Precision.MILLISECONDS

import models.akka.DSLinkMode
import models.metrics.{ ConnectionEvent, DSLinkEventDao, LinkSessionEvent, ListResult }

/**
 * InfluxDB-based implementation of [[DSLinkEventDao]].
 */
class InfluxDbDSLinkEventDao(db: Database) extends InfluxDbGenericDao(db) with DSLinkEventDao {

  /**
   * Saves a connection event as a point in 'connection' measurement.
   */
  def saveConnectionEvent(evt: ConnectionEvent): Unit = {
    val baseTags = tags("event" -> evt.event, "mode" -> evt.mode.toString,
      "version" -> evt.version, "brokerAddress" -> evt.brokerAddress)
    val baseFields = fields("sessionId" -> evt.sessionId, "linkName" -> evt.linkName,
      "compression" -> evt.compression)

    val (extraTags, extraFields) = addressData("link")(evt.linkAddress)

    val point = Point("connection", evt.ts.getMillis, baseTags ++ extraTags, baseFields ++ extraFields)
    savePoint(point)
  }

  /**
   * Filters 'connection' measurement for records satisfying the criteria.
   */
  def findConnectionEvents(linkName: Option[String], from: Option[DateTime],
                           to: Option[DateTime], limit: Int): ListResult[ConnectionEvent] = {
    val where = buildWhere(eq("linkName", linkName), ge("time", from), le("time", to))

    val query = "SELECT * FROM connection" + where + " ORDER BY time DESC LIMIT " + limit
    val fqr = db.query(query, MILLISECONDS)
    fqr map { qr =>
      val records = qr.series.flatMap(_.records)
      records map recordToConnectionEvent
    }
  }

  /**
   * Saves a session event as a point in 'link_session' measurement.
   */
  def saveSessionEvent(evt: LinkSessionEvent): Unit = {
    val duration = new Interval(evt.startTime, evt.endTime).toDuration

    val baseTags = tags("mode" -> evt.mode.toString, "brokerAddress" -> evt.brokerAddress)
    val baseFields = fields("linkName" -> evt.linkName, "endTime" -> evt.endTime.getMillis,
      "durationMs" -> duration.getMillis,
      "durationSec" -> duration.getStandardSeconds,
      "durationMin" -> duration.getStandardMinutes,
      "durationHr" -> duration.getStandardHours,
      "durationDay" -> duration.getStandardDays)

    val (extraTags, extraFields) = addressData("link")(evt.linkAddress)

    val point = Point("link_session", evt.startTime.getMillis, baseTags ++ extraTags, baseFields ++ extraFields)
    savePoint(point)
  }

  /**
   * Filters 'link_session' measurement for records satisfying the criteria.
   */
  def findSessionEvents(linkName: Option[String], from: Option[DateTime],
                        to: Option[DateTime], limit: Int): ListResult[LinkSessionEvent] = {
    val where = buildWhere(eq("linkName", linkName), ge("time", from), le("time", to))

    val query = "SELECT * FROM link_session" + where + " ORDER BY time DESC LIMIT " + limit
    val fqr = db.query(query, MILLISECONDS)
    fqr map { qr =>
      val records = qr.series.flatMap(_.records)
      records map recordToSessionEvent
    }
  }

  /**
   * Converts an InfluxDB record into a connection event instance.
   */
  private def recordToConnectionEvent(record: Record) = {
    val ts = new DateTime(record("time").asInstanceOf[Number].longValue)
    val event = record("event").asInstanceOf[String]
    val sessionId = record("sessionId").asInstanceOf[String]
    val linkId = ""
    val linkName = record("linkName").asInstanceOf[String]
    val linkAddress = record("linkIp").asInstanceOf[String]
    val mode = DSLinkMode.withName(record("mode").asInstanceOf[String])
    val version = record("version").asInstanceOf[String]
    val compression = record("compression").asInstanceOf[Boolean]
    val brokerAddress = record("brokerAddress").asInstanceOf[String]
    ConnectionEvent(ts, event, sessionId, linkId, linkName, linkAddress, mode, version,
      compression, brokerAddress)
  }

  /**
   * Converts an InfluxDB record into a link session event instance.
   */
  private def recordToSessionEvent(record: Record) = {
    val startTime = new DateTime(record("time").asInstanceOf[Number].longValue)
    val endTime = new DateTime(record("endTime").asInstanceOf[Number].longValue)
    val linkName = record("linkName").asInstanceOf[String]
    val linkAddress = record("linkIp").asInstanceOf[String]
    val mode = DSLinkMode.withName(record("mode").asInstanceOf[String])
    val brokerAddress = record("brokerAddress").asInstanceOf[String]
    LinkSessionEvent(startTime, endTime, linkName, linkAddress, mode, brokerAddress)
  }
}