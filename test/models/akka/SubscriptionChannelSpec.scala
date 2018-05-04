package models.akka

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, OverflowStrategy, ThrottleMode}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import models.ResponseEnvelope
import models.rpc.DSAResponse
import org.scalatest.{GivenWhenThen, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class SubscriptionChannelSpec extends WordSpecLike
  with Matchers
  with GivenWhenThen {

  implicit  val as = ActorSystem()
  implicit  val am = ActorMaterializer()
  implicit val ctx = scala.concurrent.ExecutionContext.global

    "Subscription channel" should {

      "deliver at least 1 message for every subscription for QoS == 0 if consumer is slow" in {

        withFlow(200, 5, QoS.Default){
          (source, flow, sink) =>

            When("consumer is slow")
            And("qos == 0")
            val runnableGraph =
              source
                .buffer(300, OverflowStrategy.backpressure)
                .via(flow)
                .throttle(1, 20.millisecond, 1, ThrottleMode.shaping)
                .toMat(sink)(Keep.right)

            val result = Await.result(runnableGraph.run(), 10 seconds)
            val totalSize = result.flatMap(_.responses).size

            Then("some messages should be delivered (last state for every sid for every pull)")
            totalSize should be > 5
            totalSize should be < 200
        }

      }

      "deliver all messages for QoS == 0 if speed equals" in {

        withFlow(200, 5, QoS.Default){
          (source, flow, sink) =>

            When("consumer as fast as producer")
            And("qos == 0")

            val runnableGraph =
              source
                .via(flow)
                .toMat(sink)(Keep.right)

            val result = Await.result(runnableGraph.run(), 10 seconds)
            val totalSize = result.flatMap(_.responses).size

            Then("All messages should be delivered")
            totalSize shouldBe 200
        }
      }

      "deliver all messages if speed is equals and QoS > 0 if consumer is slow" in {

        withFlow(200, 5, QoS.Queued){
          (source, flow, sink) =>

            When("consumer is slow")
            And("qos > 0")
            val runnableGraph =
              source
                .buffer(300, OverflowStrategy.backpressure)
                .via(flow)
                .throttle(1, 20.millisecond, 1, ThrottleMode.shaping)
                .toMat(sink)(Keep.right)

            val result = Await.result(runnableGraph.run(), 3 seconds)
            val totalSize = result.flatMap(_.responses).size

            Then("All messages should be delivered")
            totalSize shouldBe 200
        }
      }

      def withFlow(iterations: Int, sids:Int, qosLevel:QoS.Level)(assertion: (
        Source[ResponseSidAndQoS, NotUsed],
        Flow[ResponseSidAndQoS, ResponseEnvelope, NotUsed],
        Sink[ResponseEnvelope, Future[Seq[ResponseEnvelope]]]
      ) => Unit) = {

        val ch = new SubscriptionChannel(iterations, as, am)
        val flow = Flow.fromGraph(ch)
        val list = (0 to iterations - 1) map {i => ResponseSidAndQoS(DSAResponse(i, None, None, None, None), i % sids, qosLevel)}
        val source = Source(list)

        val sink = Sink.seq[ResponseEnvelope]

        assertion(source, flow, sink)

      }

    }





}
