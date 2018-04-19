package msgpack4s

import org.scalatestplus.play.PlaySpec

import org.velvia.msgpack._
import org.velvia.msgpack.SimpleCodecs._
import org.velvia.msgpack.CollectionCodecs._

import com.rojoma.simplearm.util._
import java.io.DataOutputStream

class Msgpack4sTests extends PlaySpec {

  "Msgpack5s" should {
    "convert primitives " in {
      val byteArray = pack(123)
      val num = unpack[Int](byteArray)

      num mustBe 123
    }

    "convert sequence" in {
      val intSeqCodec = new SeqCodec[Int]

      val seq1 = Seq(1, 2, 3, 4, 5)
      unpack(pack(seq1)(intSeqCodec))(intSeqCodec) mustBe seq1
    }

//    "convert streaming" in {
//      for {
//        os <- managed(resp.getOutputStream)
//        dos <- managed(new DataOutputStream(os))
//        data <- listOfObjects
//      } {
//        msgpack.pack(data, dos)
//      }
//    }
  }
}
