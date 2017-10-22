package models.metrics.influxdb

import org.joda.time.Interval

import com.paulgoldbaum.influxdbclient.{ Database, Point }

import models.metrics.{ ConnectionEvent, DSLinkEventDao, LinkSessionEvent }

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
}