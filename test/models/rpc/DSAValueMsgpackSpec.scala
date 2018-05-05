package models.rpc

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.ByteString
import org.scalatestplus.play.PlaySpec

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.stream.scaladsl._


class DSAValueMsgpackSpec extends PlaySpec {

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  "Unit tests examples for akka sreaming" should {
    "run stream test from docs" in {

      val flowUnderTest = Flow[Int].takeWhile(_ < 5)

      val future = Source(1 to 10).via(flowUnderTest).runWith(Sink.fold(Seq.empty[Int])(_ :+ _))
      val result = Await.result(future, 3.seconds)
      assert(result == (1 to 4))
    }
  }

  "Msgpack transformer" should {
    "convert play JsValue to/from http payload (binary data)" in {

      import play.api.libs.json._
      import play.api.http.websocket._

      import org.velvia.msgpack
      import org.velvia.msgpack.PlayJsonCodecs.JsValueCodec

      val msgTransformer = models.rpc.MessageFlowTransformer2.msgpackMessageFlowTransformer

      val logicFlow: Flow[JsValue, JsValue, _] = Flow[JsValue].map(x => {
        println("Busines logic: income JsValue is: " + x)
        println("Busines logic: return the same value as got")
        x
      })

      val jsValue1 = Json.parse("""{"amount":40.1,"currency":"USD","label":"10.00"}""")
      val jsValue2 = Json.parse("""{"amount":40.2,"currency":"RUR","label":"11.00"}""")

      val inDsLinkMessage = BinaryMessage(ByteString(msgpack.pack(jsValue1)))
      val inDsLinkMessage2 = BinaryMessage(ByteString(msgpack.pack(jsValue2)))

      val sourcePayload = Source(List(inDsLinkMessage, inDsLinkMessage2))

      val printingFlow: Flow[Message, Message, _] = Flow[Message].map(x => {
        println("The message: " + x)
        x
      })

      // Do not delete following line
//      val sink3 = Sink.foreach[Message](s => msgpack.unpack(s.asInstanceOf[BinaryMessage].data.toArray))

      val sink = Sink.seq[Message]

      val future = (sourcePayload via printingFlow via msgTransformer.transform(logicFlow)).runWith(sink)
      val result = Await.result(future, 3.seconds)
      assert(result == Seq(inDsLinkMessage, inDsLinkMessage2))
    }
  }
}
