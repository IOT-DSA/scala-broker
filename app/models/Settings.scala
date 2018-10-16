package models

import _root_.akka.stream.OverflowStrategy

import scala.collection.JavaConverters.asScalaSetConverter
import scala.concurrent.duration.DurationLong
import com.typesafe.config.{Config, ConfigFactory}
import play.api.libs.json.{JsArray, JsString, Json}
import play.api.libs.json.Json.toJsFieldJsValueWrapper

import scala.util.Try

/**
 * Encapsulates application settings – both hardcoded and configured in `application.conf`.
 */
object Settings {

  val rootConfig = ConfigFactory.load

  val SkipAuth = rootConfig.getBoolean("broker.skipAuth")

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
      "format" -> JsArray(Seq(cfg.getStringList("format").toArray():_*).map {
                                            s => JsString(s.asInstanceOf[String])
                                          }
      )
    )
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
    * The number means how many events we store before snapshot making.
    */
  val AkkaPersistenceSnapShotInterval = rootConfig.getInt("broker.akka-persistence-snapshot-interval")

  /**
   * Interval to wait for actors' responses.
   */
  val QueryTimeout = rootConfig.getDuration("broker.query.timeout").getSeconds.seconds

  /**
   * The maximum number of children in LIST response.
   */
  val ChildrenPerListResponse = rootConfig.getInt("broker.children.per.response")

  object Subscriptions{
    private val cfg = rootConfig.getConfig("broker.subscriptions")
    val reconnectionTimeout = cfg.intOrDefault("reconnectionTimeout", 30)
    val queueCapacity = cfg.intOrDefault("queue.capacity", 30)
    val maxBatchSize = cfg.intOrDefault("send.batch.size", 100)
    val aggregationPeriod = cfg.intOrDefault("send.batch.aggregation.period.ms", 5)
  }

  object MetricsReporters{
    private val cfg = rootConfig.getConfig("kamon")

    val zipkinConfigured = cfg.atPath("zipkin.host").isResolved() &&
      cfg.atPath("zipkin.port").isResolved &&
      Try{cfg.getInt("zipkin.port")}.isSuccess //by some reason settings from ENV variables are not null

    val statsdConfigured = cfg.atPath("statsd.hostname").isResolved() &&
      cfg.atPath("statsd.port").isResolved &&
      Try{cfg.getInt("statsd.port")}.isSuccess

    case class HostAndPort(host:String, port:Int)
  }

  /**
   * WebSocket configuration.
   */
  object WebSocket {
    private val cfg = rootConfig.getConfig("broker.ws")

    val BufferSize = cfg.getInt("buffer")
    val OnOverflow = cfg.getString("overflow.strategy").toLowerCase match {
      case "drophead"   => OverflowStrategy.dropHead
      case "droptail"   => OverflowStrategy.dropTail
      case "dropbuffer" => OverflowStrategy.dropBuffer
      case _            => OverflowStrategy.dropNew
    }
  }

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

  implicit class ConfigWrapper(config:Config){

    def getOrDefault[T](extractor: String => T)(path:String, default:T) = if(config.hasPath(path)){
      extractor(path)
    } else {
      default
    }

    def intOrDefault = getOrDefault(path => config.getInt(path))(_,_)

  }
}
