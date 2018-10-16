package models.api

import models.rpc.DSAValue

/**
  * Data types supported in DSA.
  */
object DSAValueType extends Enumeration {
  type DSAValueType = Value

  val DSAString = Value("string")
  val DSANumber = Value("number")
  val DSABoolean = Value("bool")
  val DSAArray = Value("array")
  val DSAObject = Value("map")
  val DSABinary = Value("binary")
  val DSADynamic = Value("dynamic")

  def byName(name: String) = name match {
    case "string"  => DSAString
    case "number"  => DSANumber
    case "bool"    => DSABoolean
    case "array"   => DSAArray
    case "map"     => DSAObject
    case "binary"  => DSABinary
    case "dynamic" => DSADynamic
  }

  implicit def valueTypeToValue(x: DSAValueType) = DSAValue.StringValue(x.toString)
}