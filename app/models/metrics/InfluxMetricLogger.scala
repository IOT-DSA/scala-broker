package models.metrics

import java.net.InetAddress

import scala.util.Try

import org.joda.time.{ DateTime, Interval }

import com.paulgoldbaum.influxdbclient.{ Database, Field, Point, Tag }
import com.paulgoldbaum.influxdbclient.Parameter.Consistency.ANY
import com.paulgoldbaum.influxdbclient.Parameter.Precision.MILLISECONDS

import models.akka.DSLinkMode.DSLinkMode
import models.influx._
import models.rpc.{ RequestMessage, ResponseMessage, DSARequest, DSAResponse }

/**
 * InfluxDB-based implementation of [[MetricLogger]].
 */
class InfluxMetricLogger(db: Database) extends MetricLogger {
  import models.Settings.Metrics._

  /**
   * Logs a cluster member event.
   */
  def logMemberEvent(ts: DateTime, role: String, address: String, state: String) = {
    val baseTags = tags("role" -> role, "address" -> address)
    val baseFields = fields("state" -> state)

    val point = Point("cluster", ts.getMillis, baseTags, baseFields)
    savePoint(point)
  }

  /**
   * Logs a connection event.
   */
  def logConnectionEvent(ts: DateTime, event: String, sessionId: String,
                         linkId: String, linkName: String, linkAddress: String, mode: DSLinkMode,
                         version: String, compression: Boolean, brokerAddress: String) = {

    val baseTags = tags("event" -> event, "mode" -> mode.toString, "version" -> version, "brokerAddress" -> brokerAddress)
    val baseFields = fields("sessionId" -> sessionId, "linkName" -> linkName, "compression" -> compression)

    val (extraTags, extraFields) = addressData("link")(linkAddress)

    val point = Point("connection", ts.getMillis, baseTags ++ extraTags, baseFields ++ extraFields)
    savePoint(point)
  }

  /**
   * Logs a WebSocket session.
   */
  def logWebSocketSession(startTime: DateTime, endTime: DateTime, linkName: String,
                          linkAddress: String, mode: DSLinkMode, brokerAddress: String) = {

    val duration = new Interval(startTime, endTime).toDuration

    val baseTags = tags("mode" -> mode.toString, "brokerAddress" -> brokerAddress)
    val baseFields = fields("linkName" -> linkName, "endTime" -> endTime.getMillis,
      "durationMs" -> duration.getMillis,
      "durationSec" -> duration.getStandardSeconds,
      "durationMin" -> duration.getStandardMinutes,
      "durationHr" -> duration.getStandardHours,
      "durationDay" -> duration.getStandardDays)

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
   * Logs multiple requests.
   */
  def logRequests(ts: DateTime, srcLinkName: String, srcLinkAddress: String, tgtLinkName: String,
                  requests: DSARequest*) = {

    val (srcExtraTags, srcExtraFields) = addressData("srcLink")(srcLinkAddress)

    val points = requests map { request =>
      val baseTags = tags("method" -> request.method.toString)
      val baseFields = fields("rid" -> request.rid, "srcLinkName" -> srcLinkName, "tgtLinkName" -> tgtLinkName)
      Point("request", ts.getMillis, baseTags ++ srcExtraTags, baseFields ++ srcExtraFields)
    }

    savePoints(points)
  }

  /**
   * Logs multiple responses.
   */
  def logResponses(ts: DateTime, linkName: String, linkAddress: String, responses: DSAResponse*) = {

    val (extraTags, extraFields) = addressData("link")(linkAddress)

    val points = responses map { response =>
      val baseTags = response.stream.map(ss => tags("stream" -> ss.toString)).getOrElse(Nil)
      val baseFields = fields("rid" -> response.rid, "linkName" -> linkName,
        "updateCount" -> response.updates.map(_.size).getOrElse(0),
        "error" -> response.error.isDefined)
      Point("response", ts.getMillis, baseTags ++ extraTags, baseFields ++ extraFields)
    }

    savePoints(points)
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
    val p = addPrefix(prefix) _

    fields(p("host") -> address.getHostName, p("ip") -> address.getHostAddress)
  }

  /**
   * Performs address lookup and returns a list of Tags and Fields containing the lookup data,
   * names prefixed with the given prefix.
   */
  private def geoData(prefix: String)(address: InetAddress): (Seq[Tag], Seq[Field]) = {
    val p = addPrefix(prefix) _

    GeoIp.resolve(address) map { loc =>
      val t = tags(
        p("continentCode") -> loc.continentCode,
        p("continentName") -> loc.continentName,
        p("countryCode") -> loc.countryCode,
        p("countryName") -> loc.countryName,
        p("stateCode") -> loc.stateCode,
        p("stateName") -> loc.stateName,
        p("city") -> loc.city)
      val f = fields(p("latitude") -> loc.latitude, p("longitude") -> loc.longitude)
      (t, f)
    } getOrElse Tuple2(Nil, Nil)
  }

  /**
   * Adds a prefix and capitalizes the name, if prefix is not empty.
   */
  private def addPrefix(prefix: String)(name: String) = if (prefix.isEmpty) name else prefix + name.capitalize

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