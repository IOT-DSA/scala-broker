package models.sdk

import models.api.DSAValueType
import models.rpc.DSAValue._

/**
  * SDK implicits.
  */
object Implicits {

  /* DSA data types -> Scala implicits */

  implicit def dsa2boolean(arg: DSAVal) = arg match {
    case x: BooleanValue => x.value
    case _               => throw new IllegalArgumentException("Wrong argument type, must be NumericValue")
  }

  implicit def dsa2binary(arg: DSAVal) = arg match {
    case x: BinaryValue => x.value
    case _              => throw new IllegalArgumentException("Wrong argument type, must be BinaryValue")
  }

  implicit def dsa2string(arg: DSAVal) = arg match {
    case x: StringValue => x.value
    case _              => throw new IllegalArgumentException("Wrong argument type, must be StringValue")
  }

  implicit def dsa2number(arg: DSAVal) = arg match {
    case x: NumericValue => x.value
    case _               => throw new IllegalArgumentException("Wrong argument type, must be NumericValue")
  }

  implicit def dsa2int(arg: DSAVal)(implicit cnv: DSAVal => BigDecimal) = cnv(arg).toInt

  implicit def dsa2long(arg: DSAVal)(implicit cnv: DSAVal => BigDecimal) = cnv(arg).toLong

  implicit def dsa2double(arg: DSAVal)(implicit cnv: DSAVal => BigDecimal) = cnv(arg).toDouble

  implicit def dsa2array(arg: DSAVal) = arg match {
    case x: ArrayValue => x.value
    case _             => throw new IllegalArgumentException("Wrong argument type, must be ArrayValue")
  }

  implicit def dsa2map(arg: DSAVal) = arg match {
    case x: MapValue => x.value
    case _           => throw new IllegalArgumentException("Wrong argument type, must be MapValue")
  }

  /**
    * Provides extractors for common configs.
    *
    * @param configs
    */
  implicit private[sdk] class RichConfigMap(val configs: DSAMap) extends AnyVal {

    def valueType = configs.get(ValueTypeCfg).map { vt =>
      DSAValueType.withName(vt.toString)
    }.getOrElse(DefaultValueType)

    def profile = configs.get(ProfileCfg).map(_.toString).getOrElse(DefaultProfile)

    def displayName = configs.get(DisplayCfg).map(_.toString)
  }

}
