package models.metrics.influxdb

import com.paulgoldbaum.influxdbclient.{ Database, Point }

import models.metrics.{ ResponseEventDao, ResponseMessageEvent }

/**
 * InfluxDB-based implementation of [[ResponseEventDao]].
 */
class InfluxDbResponseEventDao(db: Database) extends InfluxDbGenericDao(db) with ResponseEventDao {

  /**
   * Saves a response message event as a point in 'rsp_message' measurement.
   */
  def saveResponseMessageEvent(evt: ResponseMessageEvent): Unit = {
    val baseFields = fields("inbound" -> evt.inbound, "msgId" -> evt.msgId,
      "linkName" -> evt.linkName, "rspCount" -> evt.responseCount,
      "updates" -> evt.totalUpdates, "errors" -> evt.totalErrors)

    val (extraTags, extraFields) = addressData("link")(evt.linkAddress)

    val point = Point("rsp_message", evt.ts.getMillis, extraTags, baseFields ++ extraFields)
    savePoint(point)
  }
}