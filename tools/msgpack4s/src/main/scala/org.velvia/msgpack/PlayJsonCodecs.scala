package org.velvia.msgpack

import java.io.{ DataInputStream ⇒ DIS, DataOutputStream }

import play.api.libs.json._
//import org.velvia.msgpack._

object PlayJsonCodecs {
  import Format._
  import SimpleCodecs._
  import RawStringCodecs.StringCodec
//  import ExtraCodecs._

  implicit object JsNullCodec extends Codec[JsNull.type] {
    def pack(out: DataOutputStream, item: JsNull.type): Unit = { out.write(MP_NULL) }
    val unpackFuncMap = FastByteMap[UnpackFunc](
      MP_NULL -> { in: DIS ⇒ JsNull })
  }

  implicit object JsBooleanCodec extends Codec[JsBoolean] {
    def pack(out: DataOutputStream, item: JsBoolean): Unit = { BooleanCodec.pack(out, item.value) }
    val unpackFuncMap: FastByteMap[(DIS) => JsBoolean] = BooleanCodec.unpackFuncMap.mapValues(_.andThen(JsBoolean(_)))
  }

  implicit object JsStringCodec extends Codec[JsString] {
    def pack(out: DataOutputStream, item: JsString): Unit = { StringCodec.pack(out, item.value) }
    val unpackFuncMap = StringCodec.unpackFuncMap.mapValues(_.andThen(JsString))
  }

  implicit object JsNumberCodec extends Codec[JsNumber] {
    def pack(out: DataOutputStream, item: JsNumber): Unit = {
      val value: BigDecimal = item.value

      // TODO: Implement special case here, something with precision and scale modification
      if (value.isValidLong)
        LongCodec.pack(out, value.longValue())
      else if (value.isExactFloat) // TODO: Check this method, it does not return True even for value 2.7
        FloatCodec.pack(out, value.floatValue())
      else if (value.isExactDouble)
        DoubleCodec.pack(out, value.doubleValue())
      else
        DoubleCodec.pack(out, value.underlying().doubleValue())
    }

    val unpackFuncMap = LongCodec.unpackFuncMap.mapValues(_.andThen(b => JsNumber(BigDecimal(b)))) ++
                        FloatCodec.unpackFuncMap.mapValues(_.andThen(b => JsNumber(BigDecimal(b)))) ++
                        DoubleCodec.unpackFuncMap.mapValues(_.andThen(b => JsNumber(BigDecimal(b))))
  }

  implicit object JsArrayCodec extends Codec[JsArray] {
    lazy val seqCodec = new CollectionCodecs.SeqCodec()(JsValueCodec)
    def pack(out: DataOutputStream, a: JsArray): Unit = {
      seqCodec.pack(out, a.value)
    }
    lazy val unpackFuncMap = seqCodec
      .unpackFuncMap
      .mapValues(_.andThen(seq => JsArray(seq.toList)))
  }

  implicit object JsObjectCodec extends Codec[JsObject] {
    lazy val mapCodec = new CollectionCodecs.CMapCodec()(StringCodec, JsValueCodec)
    def pack(out: DataOutputStream, m: JsObject): Unit = { mapCodec.pack(out, m.value) }
    lazy val unpackFuncMap = mapCodec.unpackFuncMap.mapValues(_.andThen(m ⇒ JsObject(m.toList)))
  }

  implicit object JsValueCodec extends Codec[JsValue] {
    def pack(out: DataOutputStream, item: JsValue): Unit = {
      item match {
        case j: JsString    ⇒ JsStringCodec.pack(out, j)
        case j: JsNumber    ⇒ JsNumberCodec.pack(out, j)
        case j: JsBoolean   ⇒ JsBooleanCodec.pack(out, j)
        case j: JsObject    ⇒ JsObjectCodec.pack(out, j)
        case j: JsArray     ⇒ JsArrayCodec.pack(out, j)
        case j: JsNull.type ⇒ JsNullCodec.pack(out, j)
      }
    }

    val unpackFuncMap =
      JsNullCodec.unpackFuncMap.mapAs[JsValue] ++
        JsBooleanCodec.unpackFuncMap.mapAs[JsValue] ++
        JsStringCodec.unpackFuncMap.mapAs[JsValue] ++
        JsNumberCodec.unpackFuncMap.mapAs[JsValue] ++
        JsObjectCodec.unpackFuncMap.mapAs[JsValue] ++
        JsArrayCodec.unpackFuncMap.mapAs[JsValue]
  }
}
