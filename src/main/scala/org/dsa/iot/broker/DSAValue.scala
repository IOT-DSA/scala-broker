package org.dsa.iot.broker

import java.util.Base64

import org.json4s._
import org.json4s.JsonDSL.WithBigDecimal._

/**
 * Base trait for DSA-compatible values, serializable to JSON.
 */
sealed trait DSAValue[T] extends JsonExport with Serializable {
  val value: T
  def toJson: JValue

  override def toString = value.toString

  override def hashCode = value.hashCode()
  override def equals(other: Any) = other match {
    case that: DSAValue[T] => (that canEqual this) && value == that.value
    case _                 => false
  }
  def canEqual(other: Any): Boolean = other.isInstanceOf[DSAValue[T]]
}

/**
 * Defines available DSAValue types and methods for serializing them to/from JSON.
 */
object DSAValue extends JsonFactory[DSAValue[_]] {
  type Binary = Array[Byte]
  val BinaryPrefix = "\u001Bbytes:"

  type DSAMap = Map[String, DSAValue[_]]
  type DSAArray = Iterable[DSAValue[_]]

  /**
   * Deserializes a DSAValue instance from JSON.
   */
  def fromJson: PartialFunction[JValue, DSAValue[_]] = {
    case JInt(x)                                  => NumericValue(x)
    case JDouble(x)                               => NumericValue(x)
    case JDecimal(x)                              => NumericValue(x)
    case JLong(x)                                 => NumericValue(x)
    case JString(x) if x.startsWith(BinaryPrefix) => BinaryValue(stringToBinary(x))
    case JString(x)                               => StringValue(x)
    case JBool(x)                                 => BooleanValue(x)
    case x: JObject                               => MapValue(jsonToMap(x))
    case x: JArray                                => ArrayValue(jsonToArray(x))
  }

  /**
   * Translates integral values to JInt, and fractional to JDecimal.
   */
  implicit class NumericValue[T: Numeric](val value: T)(implicit f: T => JValue) extends DSAValue[T] {
    def toJson = f(value)
  }

  /**
   * Translates strings to JString.
   */
  implicit class StringValue(val value: String) extends DSAValue[String] {
    def toJson = value
  }

  /**
   * Translates booleans to JBool.
   */
  implicit class BooleanValue(val value: Boolean) extends DSAValue[Boolean] {
    def toJson = value
  }

  /**
   * Translates byte arrays to JString using Base64 encoding.
   */
  implicit class BinaryValue(val value: Binary) extends DSAValue[Binary] {
    def toJson = binaryToString(value)
  }

  /**
   * Translates Map[String, DSAValue] instances to JObject. Elements can be both simple values and
   * nested collections.
   */
  implicit class MapValue(val value: DSAMap) extends DSAValue[DSAMap] {
    def toJson = value map {
      case (k, v) => (k -> v.toJson)
    }
  }

  /**
   * Translates Iterable[DSAValue] instances to JArray. Elements can be both simple values and
   * nested collections.
   */
  implicit class ArrayValue(val value: DSAArray) extends DSAValue[DSAArray] {
    def toJson = value map (_.toJson)
  }

  /* helpers */

  def binaryToString(data: Binary) = "\u001Bbytes:" + Base64.getEncoder.encodeToString(data)

  def stringToBinary(str: String) = Base64.getDecoder.decode(str.drop(BinaryPrefix.length))

  def jsonToMap(json: JObject): DSAMap = json.obj map {
    case (name, value) => name -> fromJson(value)
  } toMap

  def jsonToArray(json: JArray): DSAArray = json.arr map fromJson
}