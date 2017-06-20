package models

import scala.collection.JavaConverters.asScalaSetConverter
import scala.concurrent.duration.DurationLong

import org.apache.kafka.streams.StreamsConfig

import javax.inject.{ Inject, Singleton }
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.json.Json.toJsFieldJsValueWrapper

/**
 * Encapsulates application settings – both hardcoded and configured in `application.conf`.
 */
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
   * DSA Nodes.
   */
  object Nodes {
    val Downstream = "downstream"
    val Upstream = "upstream"
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
   * Checking interval for undelivered messages.
   */
  val UndeliveredInterval = rootConfig.getDuration("broker.undelivered.interval").getSeconds.seconds
  
  /**
   * The maximum number of children in LIST response.
   */
  val ChildrenPerListResponse = rootConfig.getInt("broker.children.per.response")

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
      val Undelivered = ApplicationId + "_" + cfg.getString("topics.undelivered")
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