package models.metrics

import java.io.File
import java.net.InetAddress

import scala.util.{ Failure, Try }

import com.maxmind.db.CHMCache
import com.maxmind.geoip2.DatabaseReader

/**
 * Encapsulates geographic location information.
 */
case class GeoLocation(latitude: Double, longitude: Double,
                       continentCode: String, continentName: String,
                       countryCode: String, countryName: String,
                       stateCode: String, stateName: String,
                       city: String, postalCode: String, timeZone: String)

/**
 * Defines access methods to retrieve geographic information about a particular IP address.
 */
trait GeoIp {
  /**
   * Tries to retrieve the geolocation for this particular internet address.
   */
  def resolve(address: InetAddress): Try[GeoLocation]
}

/**
 * An empty implementation of GeoIp resolution.
 */
class NullGeoIp extends GeoIp {
  /**
   * Returns a `Failure`.
   */
  def resolve(address: InetAddress): Try[GeoLocation] = Failure(new NotImplementedError)
}

/**
 * Geolite-database implementation of IP resolution.
 */
class GeoLite(db: File) extends GeoIp {

  private val reader = new DatabaseReader.Builder(db).withCache(new CHMCache).build

  /**
   * Uses MaxMind GeoIp reader to locate the address in the database.
   */
  def resolve(address: InetAddress): Try[GeoLocation] = Try {
    val record = reader.city(address)
    GeoLocation(
      latitude = record.getLocation.getLatitude,
      longitude = record.getLocation.getLongitude,
      continentCode = record.getContinent.getCode,
      continentName = record.getContinent.getName,
      countryCode = record.getCountry.getIsoCode,
      countryName = record.getCountry.getName,
      stateCode = record.getMostSpecificSubdivision.getIsoCode,
      stateName = record.getMostSpecificSubdivision.getName,
      postalCode = record.getPostal.getCode,
      city = record.getCity.getName,
      timeZone = record.getLocation.getTimeZone)
  }

  sys.addShutdownHook { reader.close }
}

/**
 * Uses application config to instantiate the right version of Geolocation resolution.
 */
object GeoIp extends GeoIp {
  import models.Settings.Metrics._

  private val geo = if (UseGeoIp) new GeoLite(new File(GeoIpDb)) else new NullGeoIp

  def resolve(address: InetAddress): Try[GeoLocation] = geo.resolve(address)
}