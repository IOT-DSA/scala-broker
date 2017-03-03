package models.api

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
}