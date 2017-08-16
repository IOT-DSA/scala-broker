package models.metrics

import java.net.InetAddress

import scala.util.Try

import org.joda.time.{ DateTime, Interval }

import com.paulgoldbaum.influxdbclient.{ Database, Field, Point, Tag }
import com.paulgoldbaum.influxdbclient.Parameter.Consistency.ANY
import com.paulgoldbaum.influxdbclient.Parameter.Precision.MILLISECONDS

import models.akka.DSLinkMode.DSLinkMode
import models.influx._
import models.rpc.{ RequestMessage, ResponseMessage }

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
    val baseFields = fields("linkName" -> linkName, "compression" -> compression)

    val (extraTags, extraFields) = addressData("link")(linkAddress)

    val point = Point("handshake", ts.getMillis, baseTags ++ extraTags, baseFields ++ extraFields)
    savePoint(point)
  }

  /**
   * Logs a WebSocket session.
   */
  def logWebSocketSession(startTime: DateTime, endTime: DateTime, linkName: String,
                          linkAddress: String, mode: DSLinkMode, brokerAddress: String) = {

    val baseTags = tags("mode" -> mode.toString, "brokerAddress" -> brokerAddress)
    val baseFields = fields("linkName" -> linkName, "endTime" -> endTime.getMillis,
      "duration" -> new Interval(startTime, endTime).toDurationMillis)

    val (extraTags, extraFields) = addressData("link")(linkAddress)

    val point = Point("ws_session", startTime.getMillis, baseTags ++ extraTags, baseFields ++ extraFields)
    savePoint(point)
  }

  /**
   * Logs a request message.
   */
  def logRequestMessage(ts: DateTime, linkName: String, linkAddress: String, message: RequestMessage) = {
    val baseFields = fields("msgId" -> message.msg, "linkName" -> linkName,
      "reqCount" -> message.requests.size)

    val (extraTags, extraFields) = addressData("link")(linkAddress)

    val point = Point("req_message", ts.getMillis, extraTags, baseFields ++ extraFields)
    savePoint(point)
  }

  /**
   * Logs a response message.
   */
  def logResponseMessage(ts: DateTime, linkName: String, linkAddress: String, message: ResponseMessage) = {
    val baseFields = fields("msgId" -> message.msg, "linkName" -> linkName,
      "rspCount" -> message.responses.size)

    val (extraTags, extraFields) = addressData("link")(linkAddress)

    val point = Point("rsp_message", ts.getMillis, extraTags, baseFields ++ extraFields)
    savePoint(point)
  }

  /**
   * Extracts relevant address data and returns a list of Tags and Fields,
   * names prefixed with the given prefix.
   */
  private def addressData(prefix: String)(address: String): (Seq[Tag], Seq[Field]) = {
    val inetAddress = Try(InetAddress.getByName(address))

    val hostFields = inetAddress map hostData(prefix) getOrElse Nil

    val (geoTags, geoFields) = inetAddress map geoData(prefix) getOrElse Tuple2(Nil, Nil)

    (geoTags, hostFields ++ geoFields)
  }

  /**
   * Extracts host name and ip address from an internet address instance and returns a list
   * of fields, names prefixed with the given prefix.
   */
  private def hostData(prefix: String)(address: InetAddress): Seq[Field] = {
    def p(name: String) = if (prefix == "") name else prefix + name.capitalize

    fields(p("host") -> address.getHostName, p("ip") -> address.getHostAddress)
  }

  /**
   * Performs address lookup and returns a list of Tags and Fields containing the lookup data,
   * names prefixed with the given prefix.
   */
  private def geoData(prefix: String)(address: InetAddress): (Seq[Tag], Seq[Field]) = {
    def p(name: String) = if (prefix == "") name else prefix + name.capitalize

    GeoIp.resolve(address) map { loc =>
      val t = tags(p("continent") -> loc.continent, p("country") -> loc.country, p("city") -> loc.city)
      val f = fields(p("latitude") -> loc.latitude, p("longitude") -> loc.longitude)
      (t, f)
    } getOrElse Tuple2(Nil, Nil)
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