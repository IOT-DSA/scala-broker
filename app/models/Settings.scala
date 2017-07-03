package models

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
   * Checking interval for undelivered messages.
   */
  val UndeliveredInterval = rootConfig.getDuration("broker.undelivered.interval").getSeconds.seconds

  /**
   * The maximum number of children in LIST response.
   */
  val ChildrenPerListResponse = rootConfig.getInt("broker.children.per.response")

  /**
   * Responder configuration.
   */
  object Responder {
    private val cfg = rootConfig.getConfig("broker.responder")
    
    val ListPoolSize = cfg.getInt("list.pool.size")
    val SubscribePoolSize = cfg.getInt("subscribe.pool.size")
  }
}