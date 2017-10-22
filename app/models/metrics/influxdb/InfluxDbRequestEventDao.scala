package models.metrics.influxdb

import com.paulgoldbaum.influxdbclient.{ Database, Point }

import models.metrics.{ RequestBatchEvent, RequestEventDao, RequestMessageEvent }

/**
 * InfluxDB-based implementation of [[RequestEventDao]].
 */
class InfluxDbRequestEventDao(db: Database) extends InfluxDbGenericDao(db) with RequestEventDao {

  /**
   * Saves a request message event as a point in 'req_message' measurement.
   */
  def saveRequestMessageEvent(evt: RequestMessageEvent): Unit = {
    val baseFields = fields("inbound" -> evt.inbound, "msgId" -> evt.msgId,
      "linkName" -> evt.linkName, "reqCount" -> evt.requestCount)

    val (extraTags, extraFields) = addressData("link")(evt.linkAddress)

    val point = Point("req_message", evt.ts.getMillis, extraTags, baseFields ++ extraFields)
    savePoint(point)
  }

  /**
   * Saves a request batch event as a point in 'req_batch' measurement.
   */
  def saveRequestBatchEvent(evt: RequestBatchEvent): Unit = {
    val (srcExtraTags, srcExtraFields) = addressData("srcLink")(evt.srcLinkAddress)

    val baseTags = tags("method" -> evt.method.toString)
    val baseFields = fields("srcLinkName" -> evt.srcLinkName, "tgtLinkName" -> evt.tgtLinkName,
      "size" -> evt.size)

    val point = Point("req_batch", evt.ts.getMillis, baseTags ++ srcExtraTags, baseFields ++ srcExtraFields)
    savePoint(point)
  }
}