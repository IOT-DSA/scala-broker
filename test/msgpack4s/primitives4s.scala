package msgpack4s

import org.scalatestplus.play.PlaySpec


class Msgpack4sTests extends PlaySpec {

  "Msgpack4s" should {
    "convert primitives " in {
      import org.velvia.msgpack._
      import org.velvia.msgpack.SimpleCodecs._

      val byteArray = pack(123)
      val num = unpack[Int](byteArray)

      num mustBe 123
    }

    "convert sequence" in {
      import org.velvia.msgpack._
      import org.velvia.msgpack.CollectionCodecs._
      import org.velvia.msgpack.SimpleCodecs._

      val intSeqCodec = new SeqCodec[Int]

      val seq1 = Seq(1, 2, 3, 4, 5)
      unpack(pack(seq1)(intSeqCodec))(intSeqCodec) mustBe seq1
    }

    "convert json" in {
      import org.velvia.msgpack._
      import org.json4s.native.JsonMethods._
      import org.json4s._
      import org.json4s.JsonAST._
      import org.velvia.msgpack.Json4sCodecs._

      val aray = parse("""[1, 2.5, null, "Streater"]""")
      val map = parse("""{"bool": true, "aray": [3, -4], "map": {"inner": "me"}}""")

      unpack[JArray](pack(aray)) mustBe aray
      unpack[JValue](pack(aray)) mustBe aray
      unpack[JValue](pack(map)) mustBe map
    }

    "convert play json value" in {
      import org.velvia.msgpack
      import play.api.libs.json.{JsValue, Json}
      import org.velvia.msgpack.PlayJsonCodecs.JsValueCodec

      val js: JsValue = Json.parse("""{"amount":40.1,"currency":"USD","label":"10.00"}""")

      val p: Array[Byte] = msgpack.pack(js)

      val transJs = msgpack.unpack[JsValue](p)

      transJs mustBe js
    }

    "convert json4s value" in {
      import org.velvia.msgpack
      import org.velvia.msgpack.Json4sCodecs.JValueCodec
      import org.json4s.native.JsonMethods.parse
      import org.json4s.JsonAST.JValue


      val js: JValue = parse("""{"amount":40.1,"currency":"USD","label":"10.00"}""")
      var p: Array[Byte] = msgpack.pack(js)
      val transJs = msgpack.unpack[JValue](p)
      transJs mustBe js
    }

  }
}
