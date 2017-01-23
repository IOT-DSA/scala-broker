package models

import javax.inject.{ Inject, Singleton }
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.json.Json.toJsFieldJsValueWrapper

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
    private val cfg = rootConfig.getConfig("broker.path")

    val Root = "/"
    val Data = cfg.getString("data")
    val Defs = cfg.getString("defs")
    val Sys = cfg.getString("sys")
    val Users = cfg.getString("users")
    val Downstream = cfg.getString("downstream")
    val Upstream = cfg.getString("upstream")
  }
  
  /**
   * Used in Allowed messages sent on handshake.
   */
  val Salt = rootConfig.getInt("broker.salt")
}