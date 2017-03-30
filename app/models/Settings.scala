package models

import scala.collection.JavaConverters.asScalaSetConverter
import javax.inject.{ Inject, Singleton }
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import org.apache.kafka.streams._

@Singleton
class Settings @Inject() (val playConfig: Configuration) {

  val rootConfig = playConfig.underlying

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
   * DSA Paths.
   */
  object Paths {
    val Root = "/"
    val Data = "/data"
    val Defs = "/defs"
    val Sys = "/sys"
    val Users = "/users"
    val Downstream = "/downstream"
    val Upstream = "/upstream"
  }

  /**
   * Used in Allowed messages sent on handshake.
   */
  val Salt = rootConfig.getInt("broker.salt")

  /**
   * Kafka configuration.
   */
  object Kafka {
    private val cfg = rootConfig.getConfig("broker.kafka")

    val Enabled = cfg.getBoolean("enabled")
    val RouterAutoStart = cfg.getBoolean("router.autostart")
    val ApplicationId = cfg.getString("applicationId")
    val BrokerUrl = cfg.getString("brokerUrl")
    val ZookeeperUrl = cfg.getString("zookeeperUrl")

    val Producer = cfg.getConfig("producer")
    val Consumer = cfg.getConfig("consumer")

    object Topics {
      val ReqEnvelopeIn = ApplicationId + "_" + cfg.getString("topics.req.envelope.in")
      val ReqEnvelopeOut = ApplicationId + "_" + cfg.getString("topics.req.envelope.out")
      val RspEnvelopeIn = ApplicationId + "_" + cfg.getString("topics.rsp.envelope.in")
      val RspEnvelopeOut = ApplicationId + "_" + cfg.getString("topics.rsp.envelope.out")
    }

    val Streams = {
      val props = new java.util.Properties
      cfg.getConfig("streams").entrySet.asScala foreach { entry =>
        props.setProperty(entry.getKey, entry.getValue.unwrapped.toString)
      }
      props.setProperty(StreamsConfig.APPLICATION_ID_CONFIG, ApplicationId)
      props.setProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BrokerUrl)
      props.setProperty(StreamsConfig.ZOOKEEPER_CONNECT_CONFIG, ZookeeperUrl)
      new StreamsConfig(props)
    }
  }
}