package models.metrics.influxdb

import scala.concurrent.ExecutionContext.Implicits.global

import org.joda.time.DateTime

import com.paulgoldbaum.influxdbclient.{ Database, Point }
import com.paulgoldbaum.influxdbclient.Parameter.Precision.MILLISECONDS

import models.metrics.{ ListResult, ResponseEventDao, ResponseMessageEvent, ResponseStatsByLink }

/**
 * InfluxDB-based implementation of [[ResponseEventDao]].
 */
class InfluxDbResponseEventDao(db: Database) extends InfluxDbGenericDao(db) with ResponseEventDao {

  /**
   * Saves a response message event as a point in 'rsp_message' measurement.
   */
  def saveResponseMessageEvent(evt: ResponseMessageEvent): Unit = {
    val baseTags = tags("inbound" -> evt.inbound.toString, "linkName" -> evt.linkName)
    val baseFields = fields("msgId" -> evt.msgId, "rspCount" -> evt.responseCount,
      "updates" -> evt.totalUpdates, "errors" -> evt.totalErrors)

    val (extraTags, extraFields) = addressData("link")(evt.linkAddress)

    val point = Point("rsp_message", evt.ts.getMillis, baseTags ++ extraTags, baseFields ++ extraFields)
    savePoint(point)
  }

  /**
   * Returns response statistics by link.
   */
  def getResponseStats(from: Option[DateTime], to: Option[DateTime]): ListResult[ResponseStatsByLink] = {
    val where = buildWhere(ge("time", from), le("time", to))

    val query = """SELECT COUNT(msgId) AS msgCount, SUM(rspCount) AS rspCount,
      SUM(updates) AS updates, SUM(errors) AS errors FROM rsp_message""" +
      where + " GROUP BY linkName, inbound"
    val fqr = db.query(query, MILLISECONDS)
    fqr map (_.series map { series =>
      val linkName = series.tags("linkName").asInstanceOf[String]
      val inbound = series.tags("inbound").asInstanceOf[String].toBoolean
      val msgCount = series.records.head("msgCount").asInstanceOf[BigDecimal].intValue
      val rspCount = series.records.head("rspCount").asInstanceOf[BigDecimal].intValue
      val updateCount = series.records.head("updates").asInstanceOf[BigDecimal].intValue
      val errorCount = series.records.head("errors").asInstanceOf[BigDecimal].intValue
      ResponseStatsByLink(linkName, inbound, msgCount, rspCount, updateCount, errorCount)
    })
  }
}