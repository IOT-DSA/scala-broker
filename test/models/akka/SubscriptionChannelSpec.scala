package models.akka

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import models.ResponseEnvelope
import models.rpc.DSAResponse
import org.scalatest.{MustMatchers, WordSpecLike}
import scala.concurrent.duration._

import scala.concurrent.Await

class SubscriptionChannelSpec extends WordSpecLike with MustMatchers {

    "Subscription channel" should {
      "deliver all messages if speed is equals" in {

        implicit  val as = ActorSystem()
        implicit  val am = ActorMaterializer()

        val ch = new SubscriptionChannel(10, 20, as, am)

        val flow = Flow.fromGraph(ch)

        val list = (0 to 100) map {ResponseSidAndQoS(DSAResponse(0, None, None, None, None), _, QoS.Default)}

        val source = Source(list)

        val sink = Sink.seq[ResponseEnvelope]


        val runnableGraph =
          source
            .via(flow)
            .toMat(sink)(Keep.right)

        val result = Await.result(runnableGraph.run(), 3 seconds)
        val stop = 123
      }
    }

}
