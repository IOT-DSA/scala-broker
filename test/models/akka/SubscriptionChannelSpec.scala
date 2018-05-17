package models.akka

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, OverflowStrategy, ThrottleMode}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import models.akka.Messages.SubscriptionNotificationMessage
import models.rpc.{DSAMessage, DSAResponse, ResponseMessage}
import org.scalatest.{GivenWhenThen, Matchers, WordSpecLike}

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class SubscriptionChannelSpec extends WordSpecLike
  with Matchers
  with GivenWhenThen {

  implicit  val as = ActorSystem()
  implicit  val am = ActorMaterializer()
  implicit val ctx = as.dispatcher

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
            val totalSize = result.size

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
            val totalSize = result.size

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

            val result = Await.result(runnableGraph.run(), 5 seconds).toList.sorted
            val totalSize = result.size

            Then("All messages should be delivered")
            totalSize shouldBe 200
        }
      }

      def withFlow(iterations: Int, sids:Int, qosLevel:QoS.Level)(assertion: (
        Source[SubscriptionNotificationMessage, NotUsed],
        Flow[SubscriptionNotificationMessage, DSAMessage, NotUsed],
        Sink[DSAMessage, Future[Set[Int]]]
      ) => Unit) = {

        val stateKeeper = as.actorOf(QoSState.props(100))

        val ch = new SubscriptionChannel(stateKeeper)
        val flow = Flow.fromGraph(ch)
        val list = 0 until iterations map { i => SubscriptionNotificationMessage(0, None, List(DSAResponse(i, None, None, None, None)), i % sids, qosLevel)}
        val source = Source(list)

        val sink = Sink.fold[Set[Int], DSAMessage](Set[Int]())({case (set, next:ResponseMessage) =>
          set ++ next.responses.map(_.rid)
        })

        assertion(source, flow, sink)

      }
    }
}