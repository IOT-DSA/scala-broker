package models.rpc

import scala.math.BigDecimal.{ double2bigDecimal, int2bigDecimal, long2bigDecimal }

import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatestplus.play.PlaySpec

import play.api.libs.json.{ JsBoolean, JsNumber, JsString, JsValue, Json }
import play.api.libs.json.Json.toJsFieldJsValueWrapper

/**
 * DSAValue test suite.
 */
class DSAValueSpec extends PlaySpec with GeneratorDrivenPropertyChecks {
  import DSAValue._

  "Numeric DSAValue" should {
    "serialize Int to JSON" in forAll { (x: Int) => testJson(x, JsNumber(x)) }
    "serialize Short to JSON" in forAll { (x: Short) => testJson(x, JsNumber(x)) }
    "serialize Byte to JSON" in forAll { (x: Byte) => testJson(x, JsNumber(x)) }
    "serialize Char to JSON" in forAll { (x: Char) => testJson(x, JsNumber(x)) }
    "serialize Long to JSON" in forAll { (x: Long) => testJson(x, JsNumber(x)) }
    "serialize Float to JSON" in forAll { (x: Float) => testJson(x, JsNumber(x)) }
    "serialize Double to JSON" in forAll { (x: Double) => testJson(x, JsNumber(x)) }
    "serialize BigDecimal to JSON" in forAll { (x: BigDecimal) => testJson(x, JsNumber(x)) }
    "serialize BigInt to JSON" in forAll { (x: BigInt) => testJson(x.toDouble, JsNumber(x.toDouble)) }
  }

  "Non-numeric DSAValue" should {
    "serialize String to JSON" in forAll { (x: String) => testJson(x, JsString(x)) }
    "serialize Boolean to JSON" in forAll { (x: Boolean) => testJson(x, JsBoolean(x)) }
    "serialize Binary to JSON" in forAll { (x: Binary) =>
      val dsa = x: DSAVal
      val json = Json.toJson(dsa)
      json mustBe JsString(BinaryPrefix + java.util.Base64.getEncoder.encodeToString(x))
      // array comparison is tricky
      json.as[DSAVal] match {
        case v: BinaryValue => v.value.toList mustBe x.toList
        case v              => fail("Invalid value type: " + v)
      }
    }
  }

  "Collection DSAValue" should {
    "serialize simple Lists to JSON" in forAll { (a: String, b: Boolean, c: Int, d: Double) =>
      testJson(ArrayValue(List(a, b, c, d)), Json.arr(JsString(a), JsBoolean(b), JsNumber(c), JsNumber(d)))
    }
    "serialize simple Maps to JSON" in forAll { (a: String, b: Boolean, c: BigDecimal) =>
      testJson(MapValue(Map("a" -> a, "b" -> b, "c" -> c)),
        Json.obj("a" -> JsString(a), "b" -> JsBoolean(b), "c" -> JsNumber(c)))
    }
    "serialize nested collections to JSON" in forAll { (a: String, b: Boolean, c: BigDecimal, d: BigDecimal) =>
      val cd = ArrayValue(List(c, d))
      val bcd = MapValue(Map("b" -> b, "cd" -> cd))
      testJson(MapValue(Map("bcd" -> bcd, "a" -> a)),
        Json.obj(
          "bcd" -> Json.obj("b" -> JsBoolean(b), "cd" -> Json.arr(JsNumber(c), JsNumber(d))),
          "a" -> JsString(a)))
    }
  }

  private def testJson(req: DSAVal, js: JsValue) = {
    val json = Json.toJson(req)
    json mustBe js
    json.as[DSAVal] mustBe req
  }
}