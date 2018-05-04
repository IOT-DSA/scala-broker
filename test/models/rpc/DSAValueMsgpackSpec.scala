package models.rpc

import akka.NotUsed
import org.scalatestplus.play.PlaySpec

class DSAValueMsgpackSpec extends PlaySpec {
//  import models.rpc.MessageFlowTransformer.jsonMessageFlowTransformer

  "Msgpack transformer" should {
    "convert play js value to/from http payload" in {

      import akka.stream.scaladsl._
      import play.api.libs.json._
      import play.api.http.websocket._

//      val jsonTransformer = models.rpc.MessageFlowTransformer.jsonMessageFlowTransformer
      val msgTransformer = models.rpc.MessageFlowTransformer2.msgpackMessageFlowTransformer

      val source1 = Source(1 to 3)
      val list2 = List(JsNumber(1), JsNumber(2))
      val source2 = Source(list2)
      val flow2: Flow[JsValue, JsValue, _] = Flow[JsValue].map(x => { println("AAAA"); x })
      val source3 = source2 via flow2
      val resultFlow = msgTransformer.transform(flow2)

      val source4 = Source(List(TextMessage("Json message 1")))
      val resultSrc = source4 via resultFlow

      val sink4 = Sink.foreach[TextMessage](println(_))

      val runnable1 = Flow.fromSinkAndSource(sink4, resultSrc)

      println("That's all")
    }
  }
}
