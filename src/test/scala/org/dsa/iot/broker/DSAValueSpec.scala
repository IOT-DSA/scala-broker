package org.dsa.iot.broker

import scala.math.BigDecimal.double2bigDecimal
import scala.math.BigInt.{ int2bigInt, long2bigInt }

import org.json4s._
import org.json4s.JsonDSL.WithBigDecimal._

/**
 * DSAValue testing suite.
 */
class DSAValueSpec extends AbstractSpec {
  import DSAValue._

  "Numeric DSAValue" should {
    "serialize BigInt to JSON" in forAll { (x: BigInt) =>
      x.toJson shouldBe JInt(x)
      DSAValue.fromJson(x.toJson) shouldBe NumericValue(x)
    }
    "serialize Int to JSON" in forAll { (x: Int) =>
      x.toJson shouldBe JInt(x)
      DSAValue.fromJson(x.toJson) shouldBe NumericValue(x)
    }
    "serialize Short to JSON" in forAll { (x: Short) =>
      x.toJson shouldBe JInt(x)
      DSAValue.fromJson(x.toJson) shouldBe NumericValue(x)
    }
    "serialize Byte to JSON" in forAll { (x: Byte) =>
      x.toJson shouldBe JInt(x)
      DSAValue.fromJson(x.toJson) shouldBe NumericValue(x)
    }
    "serialize Char to JSON" in forAll { (x: Char) =>
      x.toJson shouldBe JInt(x)
      DSAValue.fromJson(x.toJson) shouldBe NumericValue(x)
    }
    "serialize Long to JSON" in forAll { (x: Long) =>
      x.toJson shouldBe JInt(x)
      DSAValue.fromJson(x.toJson) shouldBe NumericValue(x)
    }
    "serialize Float to JSON" in forAll { (x: Float) =>
      x.toJson shouldBe JDecimal(x)
      DSAValue.fromJson(x.toJson) shouldBe NumericValue(x)
    }
    "serialize Double to JSON" in forAll { (x: Double) =>
      x.toJson shouldBe JDecimal(x)
      DSAValue.fromJson(x.toJson) shouldBe NumericValue(x)
    }
    "serialize BigDecimal to JSON" in forAll { (x: BigDecimal) =>
      x.toJson shouldBe JDecimal(x)
      DSAValue.fromJson(x.toJson) shouldBe NumericValue(x)
    }
  }

  "Non-numeric DSAValue" should {
    "serialize String to JSON" in forAll { (x: String) =>
      x.toJson shouldBe JString(x)
      DSAValue.fromJson(x.toJson) shouldBe StringValue(x)
    }
    "serialize Boolean to JSON" in forAll { (x: Boolean) =>
      x.toJson shouldBe JBool(x)
      DSAValue.fromJson(x.toJson) shouldBe BooleanValue(x)
    }
    "serialize Binary to JSON" in forAll { (x: Binary) =>
      x.toJson shouldBe JString(BinaryPrefix + java.util.Base64.getEncoder.encodeToString(x))
      // array comparison is tricky
      DSAValue.fromJson(x.toJson) match {
        case v: BinaryValue => v.value.toList shouldBe x.toList
        case v              => fail("Invalid value type: " + v)
      }
    }
  }

  "Collection DSAValue" should {
    "serialize simple Lists to JSON" in forAll { (a: String, b: Boolean, c: Int, d: Double) =>
      val x = ArrayValue(List(a, b, c, d))
      x.toJson shouldBe JArray(List(JString(a), JBool(b), JInt(c), JDecimal(d)))
      DSAValue.fromJson(x.toJson) shouldBe x
    }
    "serialize simple Maps to JSON" in forAll { (a: String, b: Boolean, c: Int, d: Double) =>
      val x = MapValue(Map("a" -> a, "b" -> b, "c" -> c, "d" -> d))
      x.toJson shouldBe JObject("a" -> JString(a), "b" -> JBool(b), "c" -> JInt(c), "d" -> JDecimal(d))
      DSAValue.fromJson(x.toJson) shouldBe x
    }
    "serialize nested collections to JSON" in forAll { (a: String, b: Boolean, c: Int, d: Double) =>
      val cd = ArrayValue(List(c, d))
      val bcd = MapValue(Map("b" -> b, "cd" -> cd))
      val x = MapValue(Map("bcd" -> bcd, "a" -> a))
      x.toJson shouldBe JObject("bcd" -> JObject("b" -> JBool(b), "cd" -> JArray(List(JInt(c), JDecimal(d)))), "a" -> JString(a))
      DSAValue.fromJson(x.toJson) shouldBe x
    }
  }
}