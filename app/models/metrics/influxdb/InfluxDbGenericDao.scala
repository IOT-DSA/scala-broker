package models.metrics.influxdb

import java.net.InetAddress

import scala.util.Try

import com.paulgoldbaum.influxdbclient._
import com.paulgoldbaum.influxdbclient.Parameter.Consistency.ANY
import com.paulgoldbaum.influxdbclient.Parameter.Precision.MILLISECONDS

import models.Settings.Metrics.{ DefaultRetention, Retention }
import models.metrics.GeoIp
import play.api.Logger

/**
 * Base class for InfluxDB-based DAOs, contains helper methods for subclasses.
 */
abstract class InfluxDbGenericDao(db: Database) {

  protected val log = Logger(getClass)

  /**
   * Extracts relevant address data and returns a list of Tags and Fields,
   * names prefixed with the given prefix.
   */
  protected def addressData(prefix: String)(address: String): (Seq[Tag], Seq[Field]) = {
    val inetAddress = Try(InetAddress.getByName(address))

    val hostFields = inetAddress map hostData(prefix) getOrElse Nil

    val (geoTags, geoFields) = inetAddress map geoData(prefix) getOrElse Tuple2(Nil, Nil)

    (geoTags, hostFields ++ geoFields)
  }

  /**
   * Extracts host name and ip address from an internet address instance and returns a list
   * of fields, names prefixed with the given prefix.
   */
  protected def hostData(prefix: String)(address: InetAddress): Seq[Field] = {
    val p = addPrefix(prefix) _

    fields(p("host") -> address.getHostName, p("ip") -> address.getHostAddress)
  }

  /**
   * Performs address lookup and returns a list of Tags and Fields containing the lookup data,
   * names prefixed with the given prefix.
   */
  protected def geoData(prefix: String)(address: InetAddress): (Seq[Tag], Seq[Field]) = {
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
  protected def addPrefix(prefix: String)(name: String) = if (prefix.isEmpty) name else prefix + name.capitalize

  /**
   * Saves a point into InfluxDB. It uses MILLISECONDS precision; the retention policy is taken
   * from `metrics.retention` configuration for the point's measurement name, if not found
   * the `default` retention policy is used.
   */
  protected def savePoint(point: Point) =
    db.write(point, MILLISECONDS, ANY, Retention.getOrElse(point.key, DefaultRetention))

  /**
   * Saves a collection of points into InfluxDB. It uses MILLISECONDS precision; the retention policy
   * is taken from `metrics.retention` configuration for the first point's measurement name, if not
   * found the `default` retention policy is used.
   */
  protected def savePoints(points: Seq[Point]) = points.headOption foreach { point =>
    db.bulkWrite(points, MILLISECONDS, ANY, Retention.getOrElse(point.key, DefaultRetention))
  }
}