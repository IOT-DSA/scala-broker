package org.dsa.iot.broker

import org.json4s._
import org.json4s.JsonDSL.WithBigDecimal._

/**
 * Base trait for DSA requests.
 */
sealed trait DSARequest extends JsonExport with Serializable {
  val rid: Int
  val method: String
  def toJson: JValue

  protected def baseJson = ("rid" -> rid) ~ ("method" -> method)
}

/**
 * Provides JSON deserialization gateway for DSA requests.
 */
object DSARequest extends JsonFactory[DSARequest] {
  implicit val formats = DefaultFormats

  /**
   * Deserializes a DSARequest instance from JSON.
   */
  def fromJson: PartialFunction[JValue, DSARequest] = ListRequest.fromJson orElse
    SetRequest.fromJson orElse
    RemoveRequest.fromJson orElse
    InvokeRequest.fromJson orElse
    SubscribeRequest.fromJson orElse
    UnsubscribeRequest.fromJson orElse
    CloseRequest.fromJson
}

/**
 * Extracts a request from JSON based on the `method` field value.
 */
trait DSARequestJsonFactory[T] extends JsonFactory[T] {
  implicit val formats = DefaultFormats

  val MethodName: String
  def extract(json: JValue): T

  def fromJson: PartialFunction[JValue, T] = {
    case json if (json \ "method").extract[String] == MethodName => extract(json)
  }
}

/**
 * LIST request.
 */
case class ListRequest(rid: Int, path: String) extends DSARequest {
  val method = ListRequest.MethodName
  def toJson = baseJson ~ ("path" -> path)
}
object ListRequest extends DSARequestJsonFactory[ListRequest] {
  val MethodName = "list"
  def extract(json: JValue) = {
    val rid = (json \ "rid").extract[Int]
    val path = (json \ "path").extract[String]
    ListRequest(rid, path)
  }
}

/**
 * SET request.
 */
case class SetRequest(rid: Int, path: String, value: DSAValue[_], permit: Option[String] = None) extends DSARequest {
  val method = SetRequest.MethodName
  def toJson = baseJson ~ ("permit" -> permit) ~ ("path" -> path) ~ ("value" -> value.toJson)
}
object SetRequest extends DSARequestJsonFactory[SetRequest] {
  val MethodName = "set"
  def extract(json: JValue) = {
    val rid = (json \ "rid").extract[Int]
    val path = (json \ "path").extract[String]
    val value = DSAValue.fromJson(json \ "value")
    val permit = (json \ "permit").extract[Option[String]]
    SetRequest(rid, path, value, permit)
  }
}

/**
 * REMOVE request.
 */
case class RemoveRequest(rid: Int, path: String) extends DSARequest {
  val method = RemoveRequest.MethodName
  def toJson = baseJson ~ ("path" -> path)
}
object RemoveRequest extends DSARequestJsonFactory[RemoveRequest] {
  val MethodName = "remove"
  def extract(json: JValue) = {
    val rid = (json \ "rid").extract[Int]
    val path = (json \ "path").extract[String]
    RemoveRequest(rid, path)
  }
}

/**
 * INVOKE request.
 */
case class InvokeRequest(rid: Int, path: String, params: DSAValue.DSAMap = Map.empty,
                         permit: Option[String] = None) extends DSARequest {
  val method = InvokeRequest.MethodName
  def toJson = {
    import DSAValue._
    val obj = if (params.isEmpty) None else new Some(params.toJson)
    baseJson ~ ("permit" -> permit) ~ ("path" -> path) ~ ("params" -> obj)
  }
}
object InvokeRequest extends DSARequestJsonFactory[InvokeRequest] {
  val MethodName = "invoke"
  def extract(json: JValue) = {
    val rid = (json \ "rid").extract[Int]
    val path = (json \ "path").extract[String]
    val params = json \ "params" match {
      case x: JObject => DSAValue.jsonToMap(x)
      case _          => Map.empty[String, DSAValue[_]]
    }
    val permit = (json \ "permit").extract[Option[String]]
    InvokeRequest(rid, path, params, permit)
  }
}

/**
 * SUBSCRIBE request.
 */
case class SubscriptionPath(path: String, sid: Int, qos: Option[Int] = None) {
  def toJson = ("path" -> path) ~ ("sid" -> sid) ~ ("qos" -> qos)
}
case class SubscribeRequest(rid: Int, paths: Iterable[SubscriptionPath]) extends DSARequest {
  val method = SubscribeRequest.MethodName
  def toJson = baseJson ~ ("paths" -> paths.map(_.toJson))
}
object SubscribeRequest extends DSARequestJsonFactory[SubscribeRequest] {
  val MethodName = "subscribe"
  def extract(json: JValue) = {
    val rid = (json \ "rid").extract[Int]
    val paths = (json \ "paths").extract[List[JValue]] map { item =>
      val path = (item \ "path").extract[String]
      val sid = (item \ "sid").extract[Int]
      val qos = (item \ "qos").extract[Option[Int]]
      SubscriptionPath(path, sid, qos)
    }
    SubscribeRequest(rid, paths)
  }
}

/**
 * UNSUBSCRIBE request.
 */
case class UnsubscribeRequest(rid: Int, sids: List[Int]) extends DSARequest {
  val method = UnsubscribeRequest.MethodName
  def toJson = baseJson ~ ("sids" -> sids)
}
object UnsubscribeRequest extends DSARequestJsonFactory[UnsubscribeRequest] {
  val MethodName = "unsubscribe"
  def extract(json: JValue) = {
    val rid = (json \ "rid").extract[Int]
    val sids = (json \ "sids").extract[List[Int]]
    UnsubscribeRequest(rid, sids)
  }
}

/**
 * CLOSE request.
 */
case class CloseRequest(rid: Int) extends DSARequest {
  val method = CloseRequest.MethodName
  def toJson = baseJson
}
object CloseRequest extends DSARequestJsonFactory[CloseRequest] {
  val MethodName = "close"
  def extract(json: JValue) = {
    val rid = (json \ "rid").extract[Int]
    CloseRequest(rid)
  }
}