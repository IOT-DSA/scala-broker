package models.rpc

import models.api.DSAValueType.DSAValueType


/**
 * Base trait for DSA-compatible values.
 */
sealed trait DSAValue[T] extends Serializable {
  val value: T

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
object DSAValue {

  type Binary = Array[Byte]

  type DSAVal = DSAValue[_]
  type DSAMap = Map[String, DSAVal]
  type DSAArray = Iterable[DSAVal]

  implicit class NumericValue(val value: BigDecimal) extends DSAValue[BigDecimal]

  implicit def longToNumericValue(x: Long) = NumericValue(x)

  implicit def doubleToNumericValue(x: Double) = NumericValue(x)

  implicit def DSAValueType2Value(x: DSAValueType) = StringValue(x.toString)

  implicit class StringValue(val value: String) extends DSAValue[String]

  implicit class BooleanValue(val value: Boolean) extends DSAValue[Boolean]

  implicit class BinaryValue(val value: Binary) extends DSAValue[Binary]

  implicit class MapValue(val value: DSAMap) extends DSAValue[DSAMap]

  implicit class ArrayValue(val value: DSAArray) extends DSAValue[DSAArray]

  def array(values: DSAVal*) = ArrayValue(values.toList)

  def obj(tuples: (String, DSAVal)*) = MapValue(tuples.toMap)
}