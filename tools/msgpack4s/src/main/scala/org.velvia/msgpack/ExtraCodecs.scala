package org.velvia.msgpack

import java.io.{DataOutputStream, DataInputStream => DIS}
import java.math.{BigDecimal, BigInteger}

object ExtraCodecs {
  import SimpleCodecs.IntCodec
  import RawStringCodecs.ByteArrayCodec

  // BigDecimals are packed as an 'Int' for 'scale' value and as 'ByteArray' for BigDecimal value
  // I.e. a codec appears packed 'Int', it assums it like BigDecimal value and unpacks next unscaledValue as 'ByteArray'
  implicit object BigDecimalCodec extends Codec[BigDecimal] {

    // Packing section
    def pack(out: DataOutputStream, bd: BigDecimal) {
      IntCodec.pack(out, bd.scale)
      ByteArrayCodec.pack(out, bd.unscaledValue().toByteArray)
    }

    // Unpacking section
    // TODO: reimplement bellow section by 'mapValues' method
    private val aray = IntCodec.unpackFuncMap.aray
    private val items = for { i <- 0 to aray.length - 1 if aray(i) != null } yield {
      i.toByte -> { in: DIS =>
                    val scale: Int = aray(i).asInstanceOf[DIS => Int](in)
                    new BigDecimal(new BigInteger(ByteArrayCodec.unpack(in)), scale)
                  }
    }

    val unpackFuncMap = FastByteMap[UnpackFunc](items:_*)

  }

  // BigInteger is packed as a byte array
  implicit object BigIntegerCodec extends Codec[BigInteger] {
    def pack(out: DataOutputStream, item: BigInteger) { ByteArrayCodec.pack(out, item.toByteArray) }
    val unpackFuncMap = ByteArrayCodec.unpackFuncMap.mapValues(_.andThen(new BigInteger(_)))
  }
}