package org.dsa.iot.broker

import org.json4s._
import org.json4s.JsonDSL.WithBigDecimal._

/**
 * Converts an entity into JSON.
 */
trait JsonExport {
  def toJson: JValue
}

/**
 * Restores an entity from JSON.
 */
trait JsonFactory[T] {
  def fromJson: PartialFunction[JValue, T]
}

/**
 * Used by the router to wrap requests into an envelope with 'from' field.
 */
case class RequestEnvelope(from: String, request: DSARequest) extends JsonExport {
  val origin = request match {
    case req: SubscribeRequest   => Origin(from, "sid", req.path.sid)
    case req: UnsubscribeRequest => Origin(from, "sid", req.sid)
    case req @ _                 => Origin(from, "rid", req.rid)
  }
  val toJson = ("from" -> from) ~ ("request" -> request.toJson)
}

/**
 * Factory for request envelopes.
 */
object RequestEnvelope extends JsonFactory[RequestEnvelope] {
  implicit val formats = DefaultFormats

  def fromJson = {
    case json =>
      val from = (json \ "from").extract[String]
      val request = DSARequest.fromJson(json \ "request")
      RequestEnvelope(from, request)
  }
}

/**
 * Combines the originator (`from`), and either RID or SID.
 */
case class Origin(from: String, idName: String, id: Int) extends JsonExport {
  def toKeyString = s"$from:$idName=$id"
  def toJson = ("from" -> from) ~ ("idName" -> idName) ~ ("id" -> id)
}

/**
 * Origin factory.
 */
object Origin extends JsonFactory[Origin] {
  implicit val formats = DefaultFormats

  def fromJson = {
    case json =>
      val from = (json \ "from").extract[String]
      val idName = (json \ "idName").extract[String]
      val id = (json \ "id").extract[Int]
      Origin(from, idName, id)
  }
}

/**
 * Encapsulates an active subscription: the method and path associated with a list of origins listening
 * for updates.
 */
case class Subscription(rid: Int, method: String, path: String, origins: Set[Origin]) extends JsonExport {
  val pathKey = method + ":" + path
  val isEmpty = origins.isEmpty
  def +(origin: Origin) = Subscription(this.rid, this.method, this.path, this.origins + origin)
  def -(origin: Origin) = Subscription(this.rid, this.method, this.path, this.origins - origin)
  def toJson = ("rid" -> rid) ~ ("method" -> method) ~ ("path" -> path) ~ ("origins" -> origins.map(_.toJson))
}

/**
 * Subscription factory.
 */
object Subscription extends JsonFactory[Subscription] {
  implicit val formats = DefaultFormats

  def apply(rid: Int, method: String, path: String, origin: Origin): Subscription =
    apply(rid, method, path, Set(origin))

  def fromJson = {
    case json =>
      val rid = (json \ "rid").extract[Int]
      val method = (json \ "method").extract[String]
      val path = (json \ "path").extract[String]
      val origins = (json \ "origins").children map (Origin.fromJson)
      Subscription(rid, method, path, origins.toSet)
  }
}