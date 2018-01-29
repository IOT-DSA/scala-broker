package models.metrics.influxdb

import scala.concurrent.ExecutionContext.Implicits.global

import org.joda.time.DateTime

import com.paulgoldbaum.influxdbclient.{ Database, Point }
import com.paulgoldbaum.influxdbclient.Parameter.Precision.MILLISECONDS

import javax.inject.Inject
import models.metrics._

/**
 * InfluxDB-based implementation of [[RequestEventDao]].
 */
class InfluxDbRequestEventDao @Inject() (db: Database) extends InfluxDbGenericDao(db) with RequestEventDao {

  /**
   * Saves a request message event as a point in 'req_message' measurement.
   */
  def saveRequestMessageEvent(evt: RequestMessageEvent): Unit = {
    val baseTags = tags("inbound" -> evt.inbound.toString, "linkName" -> evt.linkName)
    val baseFields = fields("msgId" -> evt.msgId, "reqCount" -> evt.requestCount)

    val (extraTags, extraFields) = addressData("link")(evt.linkAddress)

    val point = Point("req_message", evt.ts.getMillis, baseTags ++ extraTags, baseFields ++ extraFields)
    savePoint(point)
  }

  /**
   * Returns request statistics.
   */
  def getRequestStats(from: Option[DateTime], to: Option[DateTime]): ListResult[RequestStatsByLink] = {
    val where = buildWhere(ge("time", from), le("time", to))

    val query = "SELECT COUNT(msgId) AS msgCount, SUM(reqCount) AS reqCount FROM req_message" +
      where + " GROUP BY linkName, inbound"
    val fqr = db.query(query, MILLISECONDS)
    fqr map (_.series map { series =>
      val linkName = series.tags("linkName").asInstanceOf[String]
      val inbound = series.tags("inbound").asInstanceOf[String].toBoolean
      val msgCount = series.records.head("msgCount").asInstanceOf[BigDecimal].intValue
      val reqCount = series.records.head("reqCount").asInstanceOf[BigDecimal].intValue
      RequestStatsByLink(linkName, inbound, msgCount, reqCount)
    })
  }

  /**
   * Saves a request batch event as a point in 'req_batch' measurement.
   */
  def saveRequestBatchEvent(evt: RequestBatchEvent): Unit = {
    val (srcExtraTags, srcExtraFields) = addressData("srcLink")(evt.srcLinkAddress)

    val baseTags = tags("srcLinkName" -> evt.srcLinkName, "tgtLinkName" -> evt.tgtLinkName,
      "method" -> evt.method.toString)
    val baseFields = fields("size" -> evt.size)

    val point = Point("req_batch", evt.ts.getMillis, baseTags ++ srcExtraTags, baseFields ++ srcExtraFields)
    savePoint(point)
  }

  /**
   * Returns request statistics by method.
   */
  def getRequestBatchStats(from: Option[DateTime], to: Option[DateTime]): ListResult[RequestStatsByMethod] = {
    val where = buildWhere(ge("time", from), le("time", to))

    val query = "SELECT SUM(size) AS size FROM req_batch" + where +
      " GROUP BY srcLinkName, tgtLinkName, method"
    val fqr = db.query(query, MILLISECONDS)
    fqr map { qr =>
      val rows = qr.series map { series =>
        val srcLinkName = series.tags("srcLinkName").asInstanceOf[String]
        val tgtLinkName = series.tags("tgtLinkName").asInstanceOf[String]
        val method = series.tags("method").asInstanceOf[String]
        val size = series.records.head("size").asInstanceOf[BigDecimal].intValue
        (srcLinkName, tgtLinkName, method, size)
      }
      rows groupBy (r => (r._1, r._2)) mapValues {
        _.map(t => t._3 -> t._4).toMap
      } map {
        case ((srcLinkName, tgtLinkName), byMethod) => RequestStatsByMethod(srcLinkName, tgtLinkName, byMethod)
      } toList
    }
  }
}