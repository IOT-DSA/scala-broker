package models

import java.util.Base64

import models.akka.QoS
import play.api.data.validation.ValidationError
import play.api.libs.functional.syntax._
import play.api.libs.json._

/**
 * Types and utility functions for models.
 */
package object rpc {
  import DSAValue._

  val BinaryPrefix = "\u001Bbytes:"

  /**
   * Implements JSON Reads for the given Enumeration type.
   */
  def enumReads[E <: Enumeration](enum: E): Reads[E#Value] = new Reads[E#Value] {
    def reads(json: JsValue): JsResult[E#Value] = json match {
      case JsString(s) => {
        try {
          JsSuccess(enum.withName(s))
        } catch {
          case _: NoSuchElementException => JsError(s"Enumeration expected of type: '${enum.getClass}', but it does not appear to contain the value: '$s'")
        }
      }
      case _ => JsError("String value expected")
    }
  }

  /**
   * Implements JSON Writes for the given Enumeration type.
   */
  implicit def enumWrites[E <: Enumeration]: Writes[E#Value] = new Writes[E#Value] {
    def writes(v: E#Value): JsValue = JsString(v.toString)
  }

  /**
   * DSAValue <-> JSON
   */
  implicit val DSAValueFormat: Format[DSAVal] = new Format[DSAVal] {

    def writes(dsa: DSAVal) = dsa match {
      case null            => JsNull
      case v: NumericValue => Json.toJson(v.value)
      case v: StringValue  => Json.toJson(v.value)
      case v: BooleanValue => Json.toJson(v.value)
      case v: BinaryValue  => Json.toJson(binaryToString(v.value))
      case v: MapValue     => Json.toJson(valueMapToJson(v.value))
      case v: ArrayValue   => Json.toJson(valueListToJson(v.value))
    }

    def reads(json: JsValue) = try {
      JsSuccess(json2dsa(json))
    } catch {
      case e: RuntimeException => JsError(JsonValidationError(e.getMessage))
    }

    private def json2dsa: PartialFunction[JsValue, DSAVal] = {
      case JsNull                                    => null
      case JsNumber(x)                               => new NumericValue(x)
      case JsString(x) if x.startsWith(BinaryPrefix) => BinaryValue(stringToBinary(x))
      case JsString(x)                               => new StringValue(x)
      case JsBoolean(x)                              => new BooleanValue(x)
      case JsObject(fields)                          => new MapValue(jsonMapToValues(fields.toMap))
      case JsArray(items)                            => new ArrayValue(jsonListToValues(items))
    }
  }

  /**
   * DSARequest <-> JSON
   */
  implicit val DSARequestFormat: Format[DSARequest] = new Format[DSARequest] {
    import DSAMethod._

    implicit val SubscriptionPathFormat = Json.format[SubscriptionPath]

    implicit val DSAMethodReads = enumReads(DSAMethod)

    val ListRequestFormat = Json.format[ListRequest]
    val SetRequestFormat = (
      (__ \ 'rid).format[Int] ~
      (__ \ 'path).format[String] ~
      (__ \ 'value).format[DSAVal] ~
      (__ \ 'permit).formatNullable[String])(SetRequest, unlift(SetRequest.unapply))
    val RemoveRequestFormat = Json.format[RemoveRequest]
    val InvokeRequestFormat = (
      (__ \ 'rid).format[Int] ~
      (__ \ 'path).format[String] ~
      (__ \ 'params).formatNullable[DSAMap].inmap(emptyIfNone, noneIfEmpty) ~
      (__ \ 'permit).formatNullable[String])(InvokeRequest, unlift(InvokeRequest.unapply))
    val SubscribeRequestFormat = Json.format[SubscribeRequest]
    val UnsubscribeRequestFormat = Json.format[UnsubscribeRequest]
    val CloseRequestFormat = Json.format[CloseRequest]

    def writes(req: DSARequest) = baseJson(req) ++ (req match {
      case x: ListRequest        => ListRequestFormat.writes(x)
      case x: SetRequest         => SetRequestFormat.writes(x)
      case x: RemoveRequest      => RemoveRequestFormat.writes(x)
      case x: InvokeRequest      => InvokeRequestFormat.writes(x)
      case x: SubscribeRequest   => SubscribeRequestFormat.writes(x)
      case x: UnsubscribeRequest => UnsubscribeRequestFormat.writes(x)
      case x: CloseRequest       => CloseRequestFormat.writes(x)
    }).as[JsObject]

    def reads(json: JsValue) = (json \ "method").as[DSAMethod] match {
      case List        => ListRequestFormat.reads(json)
      case Set         => SetRequestFormat.reads(json)
      case Remove      => RemoveRequestFormat.reads(json)
      case Invoke      => InvokeRequestFormat.reads(json)
      case Subscribe   => SubscribeRequestFormat.reads(json)
      case Unsubscribe => UnsubscribeRequestFormat.reads(json)
      case Close       => CloseRequestFormat.reads(json)
      case unknown     => JsError(s"Unknown method: '$unknown'")
    }

    private def baseJson(req: DSARequest) = Json.obj("rid" -> req.rid, "method" -> req.method)

    private def noneIfEmpty(map: DSAMap) = if (map.isEmpty) None else Some(map)

    private def emptyIfNone(opt: Option[DSAMap]) = opt getOrElse Map.empty
  }

  /**
   * DSAError <-> JSON
   */
  implicit val DSAErrorFormat: Format[DSAError] = (
    (__ \ 'msg).formatNullable[String] ~
    (__ \ 'type).formatNullable[String] ~
    (__ \ 'phase).formatNullable[String] ~
    (__ \ 'path).formatNullable[String] ~
    (__ \ 'detail).formatNullable[String])(DSAError, unlift(DSAError.unapply))

  /**
   * ColumnInfo <-> JSON
   */
  implicit val ColumnInfoFormat: Format[ColumnInfo] = (
    (__ \ 'name).format[String] ~
    (__ \ 'type).format[String])(ColumnInfo, unlift(ColumnInfo.unapply))

  /**
   * DSAResponse <-> JSON
   */
  implicit val StreamStateReads = enumReads(StreamState)
  implicit val DSAResponseFormat: Format[DSAResponse] = Json.format[DSAResponse]

  implicit val QoSFormat: Format[models.akka.QoS.Level] = new Format[QoS.Level] {
    override def reads(json: JsValue): JsResult[QoS.Level] = JsSuccess(QoS(json.as[Int]))
    override def writes(o: QoS.Level): JsValue = JsNumber(o.index)
  }

  /**
   * DSAMessage <-> JSON
   */
  implicit val DSAMessageFormat: Format[DSAMessage] = new Format[DSAMessage] {

    val AllowedMessageFormat = Json.format[AllowedMessage]

    val PingMessageFormat = Json.format[PingMessage]

    val PongMessageFormat = Json.format[PongMessage]

    val RequestMessageFormat: Format[RequestMessage] = (
      (__ \ 'msg).format[Int] ~
      (__ \ 'ack).formatNullable[Int] ~
      (__ \ 'requests).format[List[DSARequest]])(RequestMessage, unlift(RequestMessage.unapply))

    val ResponseMessageFormat: Format[ResponseMessage] = (
      (__ \ 'msg).format[Int] ~
      (__ \ 'ack).formatNullable[Int] ~
      (__ \ 'responses).format[List[DSAResponse]])(ResponseMessage, unlift(ResponseMessage.unapply))

    val SubscriptionNotificationMessageFormat:Format[SubscriptionNotificationMessage] = (
      (__ \ 'msg).format[Int] ~
      (__ \ 'ack).formatNullable[Int] ~
      (__ \ 'responses).format[List[DSAResponse]] ~
      (__ \ 'sid).format[Int] ~
      (__ \ 'qos).format[models.akka.QoS.Level]
      )(SubscriptionNotificationMessage, unlift(SubscriptionNotificationMessage.unapply))


    def writes(msg: DSAMessage) = msg match {
      case EmptyMessage       => Json.obj()
      case m: AllowedMessage  => AllowedMessageFormat.writes(m)
      case m: PingMessage     => PingMessageFormat.writes(m)
      case m: PongMessage     => PongMessageFormat.writes(m)
      case m: RequestMessage  => RequestMessageFormat.writes(m)
      case m: ResponseMessage => ResponseMessageFormat.writes(m)
      case m: SubscriptionNotificationMessage => SubscriptionNotificationMessageFormat.writes(m)
    }

    def reads(json: JsValue) = json match {
      case JsObject(fields) if fields.isEmpty => JsSuccess(EmptyMessage)
      case JsObject(fields) if fields.contains("allowed") => AllowedMessageFormat.reads(json)
      case JsObject(fields) if fields.contains("requests") => RequestMessageFormat.reads(json)
      case JsObject(fields) if fields.contains("responses") => ResponseMessageFormat.reads(json)
      case JsObject(fields) if fields.contains("msg") => PingMessageFormat.reads(json)
      case JsObject(fields) if fields.contains("ack") => PongMessageFormat.reads(json)
      case JsObject(fields) if fields.contains("sid") => SubscriptionNotificationMessageFormat.reads(json)
      case _ => JsError("Unrecognized message: " + json)
    }
  }

  /* helpers */

  def binaryToString(data: Binary): String = BinaryPrefix + Base64.getEncoder.encodeToString(data)

  def stringToBinary(str: String): Binary = Base64.getDecoder.decode(str.drop(BinaryPrefix.length))

  def jsonMapToValues(fields: Map[String, JsValue]) = fields.map { case (a, b) => a -> b.as[DSAVal] }.toMap[String, DSAVal]

  def jsonListToValues(items: Iterable[JsValue]) = items map (_.as[DSAVal]) toList

  def valueMapToJson(fields: Map[String, DSAVal]) = fields map { case (a, b) => (a -> Json.toJson(b)) }

  def valueListToJson(items: Iterable[DSAVal]) = items map (Json.toJson(_))

  /**
   * Extracts SID from an update row.
   */
  val extractSid: PartialFunction[DSAVal, Int] = {
    case v: ArrayValue => v.value.head.asInstanceOf[NumericValue].value.intValue
    case v: MapValue   => v.value("sid").asInstanceOf[NumericValue].value.intValue
  }

  /**
   * Replaces SID in an update row.
   */
  def replaceSid(row: DSAVal, sid: Int) = row match {
    case v: ArrayValue => ArrayValue(sid :: v.value.tail.toList)
    case v: MapValue   => MapValue(v.value + ("sid" -> sid))
    case v             => v
  }
}