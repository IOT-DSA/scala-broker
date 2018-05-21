package org.velvia

import org.scalatest.Matchers
import org.scalatest.FunSpec

class MsgPackPlaySpec extends FunSpec with Matchers {
  import play.api.libs.json.{JsValue, Json}
  import msgpack.PlayJsonCodecs.JsValueCodec

  describe("Test MsgPackPlay json codecs") {


    val testData : Array[String] = Array("""{"amount":40.1,"currency":"USD","label":"10.00"}"""
                                        , """{"ids":[1, 2, 3, 4], "name": "test ids"}"""
                                        , """{"myobj": {"id": 1, "name": "sub_obj", "value" : 3, "extra" : 3.1415}, "name": "test ids"}"""
                                        , """{"amount":9812312.1912312,"currency":"USD","label":"10.00"}"""
    )

    testData foreach { strJs: String =>
      it("Should code/decode: " + strJs) {
        val js: JsValue = Json.parse(strJs)

        val p: Array[Byte] = msgpack.pack(js)

        val u_js = msgpack.unpack[JsValue](p)

        u_js should equal (js)
      }
    }

  }
}
