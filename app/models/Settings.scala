package models

import scala.collection.JavaConverters._
import scala.concurrent.duration.DurationLong

import com.typesafe.config.ConfigFactory

import play.api.libs.json.Json
import play.api.libs.json.Json.toJsFieldJsValueWrapper

/**
 * Encapsulates application settings – both hardcoded and configured in `application.conf`.
 */
object Settings {

  val rootConfig = ConfigFactory.load
  
  /**
   * Broker name.
   */
  val BrokerName = rootConfig.getString("broker.name")

  /**
   * DSA Server Configuration.
   */
  val ServerConfiguration = {
    val cfg = rootConfig.getConfig("broker.server-config")
    Json.obj(
      "dsId" -> cfg.getString("dsId"),
      "publicKey" -> cfg.getString("publicKey"),
      "wsUri" -> cfg.getString("wsUrl"),
      "httpUri" -> cfg.getString("httpUri"),
      "tempKey" -> cfg.getString("tempKey"),
      "salt" -> cfg.getString("salt"),
      "version" -> cfg.getString("version"),
      "updateInterval" -> cfg.getInt("updateInterval"),
      "format" -> cfg.getString("format"))
  }

  /**
   * DSA Nodes.
   */
  object Nodes {
    val Root = "broker"
    val Downstream = "downstream"
    val Upstream = "upstream"
    val Data = "data"
    val Defs = "defs"
    val Sys = "sys"
    val Users = "users"
  }

  /**
   * DSA Paths.
   */
  object Paths {
    val Root = "/"
    val Data = "/data"
    val Defs = "/defs"
    val Sys = "/sys"
    val Users = "/users"
    val Downstream = Root + Nodes.Downstream
    val Upstream = Root + Nodes.Upstream
  }

  /**
   * Used in Allowed messages sent on handshake.
   */
  val Salt = rootConfig.getInt("broker.salt")

  /**
   * Interval to wait for actors' responses.
   */
  val QueryTimeout = rootConfig.getDuration("broker.query.timeout").getSeconds.seconds

  /**
   * The maximum number of children in LIST response.
   */
  val ChildrenPerListResponse = rootConfig.getInt("broker.children.per.response")

  /**
   * Responder configuration.
   */
  object Responder {
    private val cfg = rootConfig.getConfig("broker.responder")

    val GroupCallEngine = cfg.getString("group.call.engine")
    val ListPoolSize = cfg.getInt("list.pool.size")
    val SubscribePoolSize = cfg.getInt("subscribe.pool.size")
  }

  /**
   * The number of shards in the downstream pool.
   */
  val DownstreamShardCount = rootConfig.getInt("broker.downstream.shard.count")

  /**
   * Metrics collector configuration
   */
  object Metrics {
    private val cfg = rootConfig.getConfig("broker.metrics")

    val Collector = cfg.getString("collector")
    val Retention = cfg.getConfig("retention").entrySet.asScala map { entry =>
      entry.getKey -> entry.getValue.unwrapped.toString
    } toMap

    val DefaultRetention = Retention.get("default").orNull

    val UseGeoIp = cfg.hasPath("geoip.db")
    val GeoIpDb = if (UseGeoIp) cfg.getString("geoip.db") else null
  }

  /**
   * InfluxDB configuration.
   */
  object InfluxDb {
    private val cfg = rootConfig.getConfig("influxdb")

    val Host = cfg.getString("host")
    val Port = cfg.getInt("port")
    val DbName = cfg.getString("database")
  }

  /**
   * JDBC configuration.
   */
  object JDBC {
    private val cfg = rootConfig.getConfig("db")

    val Driver = cfg.getString("default.driver")
    val Url = cfg.getString("default.url")
  }

  /**
   * Logging options.
   */
  object Logging {
    private val cfg = rootConfig.getConfig("broker.logging")

    val ShowWebSocketPayload = cfg.getBoolean("show.ws.payload")
  }
}