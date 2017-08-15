package models.metrics

import java.net.InetAddress

import scala.util.Try

import org.joda.time.DateTime

import com.paulgoldbaum.influxdbclient.{ Database, Point }
import com.paulgoldbaum.influxdbclient.Parameter.Consistency.ANY
import com.paulgoldbaum.influxdbclient.Parameter.Precision.MILLISECONDS

import models.akka.DSLinkMode.DSLinkMode
import models.influx._

/**
 * InfluxDB-based implementation of [[MetricLogger]].
 */
class InfluxMetricLogger(db: Database) extends MetricLogger {
  import models.Settings.Metrics._

  /**
   * Logs a handshake attempt.
   */
  def logHandshake(ts: DateTime,
                   linkId: String, linkName: String, linkAddress: String, mode: DSLinkMode,
                   version: String, compression: Boolean, brokerAddress: String) = {

    val baseTags = tags("mode" -> mode.toString, "version" -> version, "brokerAddress" -> brokerAddress)
    val baseFields = fields( "compression" -> compression.toString)

    val address = Try(InetAddress.getByName(linkAddress))

    val linkFields = address map { addr =>
      fields("linkHost" -> addr.getHostName, "linkIp" -> addr.getHostAddress)
    } getOrElse Nil

    val (geoTags, geoFields) = address flatMap GeoIp.resolve map { loc =>
      val t = tags("continent" -> loc.continent, "country" -> loc.country, "city" -> loc.city)
      val f = fields("latitude" -> loc.latitude, "longitude" -> loc.longitude)
      (t, f)
    } getOrElse Tuple2(Nil, Nil)

    val point = Point("handshake", ts.getMillis, baseTags ++ geoTags, baseFields ++ linkFields ++ geoFields)
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