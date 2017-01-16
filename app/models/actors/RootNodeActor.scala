package models.actors

import akka.actor.{ Actor, ActorRef, PoisonPill, Props, actorRef2Scala }
import play.api.Logger
import play.api.libs.json.{ JsObject, Json, JsValue }
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import models._
import play.api.cache.CacheApi
import models.DSAValue.ArrayValue

/**
 * Services requests that need to be handled by the broker.
 */
class RootNodeActor(cache: CacheApi) extends Actor {
  import RootNodeActor._

  private val log = Logger(getClass)

  /**
   * Registers to receive requests for multiple paths.
   */
  override def preStart() = {
    cache.set(RootPath, self)
    cache.set(DefsPath, self)
    cache.set(SysPath, self)
    cache.set(UsersPath, self)
    cache.set(DownstreamPath, self)
    cache.set(UpstreamPath, self)

    log.debug("RootNode actor initialized")
  }

  /**
   * Handles broker requests.
   */
  def receive = {
    case env @ RequestEnvelope(ListRequest(rid, RootPath)) =>
      log.debug(s"Request received: $env")
      val list = List(ArrayValue(List("data", 1)), ArrayValue(List("defs", 2)))
      sender ! ResponseEnvelope(DSAResponse(rid = rid, updates = Some(list)))
    case RequestEnvelope(req) =>
      log.warn(s"Unsupported request received: $req")
      sender ! ResponseEnvelope(DSAResponse(rid = req.rid, error = Some(DSAError(msg = Some("Unsupported")))))
    case msg @ _ =>
      log.error(s"Invalid message received: $msg")
  }
}

/**
 * Provides contants and factory methods.
 */
object RootNodeActor {
  val RootPath = "/"
  val DefsPath = "/defs"
  val SysPath = "/sys"
  val UsersPath = "/users"
  val DownstreamPath = "/downstream"
  val UpstreamPath = "/upstream"

  def props(cache: CacheApi) = Props(new RootNodeActor(cache))
}