import java.util.Base64

import models.DSAValue
import play.api.data.validation.ValidationError
import play.api.libs.json._

package object models {
  import DSAValue._

  val BinaryPrefix = "\u001Bbytes:"

  /**
   * DSAValue -> JSON
   */
  implicit val DSAValueWrites: Writes[DSAVal] = new Writes[DSAVal] {
    def writes(dsa: DSAVal) = dsa match {
      case v: NumericValue => Json.toJson(v.value)
      case v: StringValue  => Json.toJson(v.value)
      case v: BooleanValue => Json.toJson(v.value)
      case v: BinaryValue  => Json.toJson(binaryToString(v.value))
      case v: MapValue     => Json.toJson(valueMapToJson(v.value))
      case v: ArrayValue   => Json.toJson(valueListToJson(v.value))
    }
  }

  /**
   * JSON -> DSAValue
   */
  implicit val DSAValueReads: Reads[DSAVal] = new Reads[DSAVal] {

    def reads(json: JsValue) = try {
      val dsa = json2dsa(json)
      JsSuccess(dsa)
    } catch {
      case e: RuntimeException => JsError(ValidationError(e.getMessage))
    }

    private def json2dsa: PartialFunction[JsValue, DSAVal] = {
      case JsNumber(x)                               => new NumericValue(x)
      case JsString(x) if x.startsWith(BinaryPrefix) => BinaryValue(stringToBinary(x))
      case JsString(x)                               => new StringValue(x)
      case JsBoolean(x)                              => new BooleanValue(x)
      case JsObject(fields)                          => new MapValue(jsonMapToValues(fields.toMap))
      case JsArray(items)                            => new ArrayValue(jsonListToValues(items))
    }
  }

  /* helpers */

  def binaryToString(data: Binary): String = BinaryPrefix + Base64.getEncoder.encodeToString(data)

  def stringToBinary(str: String): Binary = Base64.getDecoder.decode(str.drop(BinaryPrefix.length))

  def jsonMapToValues(fields: Map[String, JsValue]) = fields map { case (a, b) => a -> b.as[DSAVal] }

  def jsonListToValues(items: Iterable[JsValue]) = items map (_.as[DSAVal])

  def valueMapToJson(fields: Map[String, DSAVal]) = fields map { case (a, b) => (a -> Json.toJson(b)) }

  def valueListToJson(items: Iterable[DSAVal]) = items map (Json.toJson(_))
}