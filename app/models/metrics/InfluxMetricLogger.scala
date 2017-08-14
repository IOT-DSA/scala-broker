package models.metrics

import org.joda.time.DateTime

import com.paulgoldbaum.influxdbclient.Database
import com.paulgoldbaum.influxdbclient.Parameter.Consistency.ANY
import com.paulgoldbaum.influxdbclient.Parameter.Precision.MILLISECONDS
import com.paulgoldbaum.influxdbclient.Point

import models.akka.DSLinkMode.DSLinkMode

/**
 * InfluxDB-based implementation of [[MetricLogger]].
 */
class InfluxMetricLogger(db: Database) extends MetricLogger {
  import models.Settings.Metrics._

  def logHandshake(ts: DateTime,
                   linkId: String, linkName: String, linkAddress: String, mode: DSLinkMode,
                   version: String, compression: Boolean, brokerAddress: String) = {
    val point = Point("handshake", ts.getMillis)
      .addTag("mode", mode.toString)
      .addTag("version", version)
      .addTag("brokerAddress", brokerAddress)
      .addField("linkId", linkId)
      .addField("linkAddress", linkAddress)
      .addField("compression", compression.toString)
    savePoint(point)
  }

  /**
   * Saves a point into InfluxDB. It uses MILLISECONDS precision; the retention policy is taken
   * from `metrics.retention` configuration for the point's measurement name, if not found
   * the `default` retention policy is used.
   */
  private def savePoint(point: Point) =
    db.write(point, MILLISECONDS, ANY, Retention.getOrElse(point.key, DefaultRetention))

  /**
   * Saves a collection of points into InfluxDB. It uses MILLISECONDS precision; the retention policy
   * is taken from `metrics.retention` configuration for the first point's measurement name, if not
   * found the `default` retention policy is used.
   */
  private def savePoints(points: Seq[Point]) = points.headOption foreach { point =>
    db.bulkWrite(points, MILLISECONDS, ANY, Retention.getOrElse(point.key, DefaultRetention))
  }
}