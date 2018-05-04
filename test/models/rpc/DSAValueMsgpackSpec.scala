package models.rpc

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
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

      implicit val system = ActorSystem()
      implicit val materializer = ActorMaterializer()

     //val source1 = Source(1 to 3)
      val list2 = List(JsNumber(1), JsNumber(2))
      val source1 = Source(list2)

      val flow2: Flow[JsValue, JsValue, _] = Flow[JsValue].map(x => { println("AAAA"); x })

      val source2 = source1 via flow2

      val resultFlow = msgTransformer.transform(flow2)

      val source3 = Source(List(TextMessage("Json message 1")))

      val resultSrc = source3 via resultFlow

      val sink4 = Sink.foreach[TextMessage](println(_))

      val messageSink = Sink.foreach[Message](println(_))

      //first option
      source3.to(sink4).run()

      // second
      resultSrc.to(messageSink).run()


      println("That's all")
    }
  }
}
