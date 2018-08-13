package models.bench

import scala.concurrent.duration.DurationInt
import org.scalatest.Inside
import akka.routing.ActorRefRoutee
import akka.testkit.TestProbe
import models.OutRequestEnvelope
import models.akka.{AbstractActorSpec, ConnectionInfo}
import models.akka.Messages.ConnectEndpoint
import models.rpc._

/**
 * Test suite for BenchmarkResponder.
 */
class BenchmarkResponderSpec extends AbstractActorSpec with Inside {
  import BenchmarkResponder._
  import models.rpc.DSAValue._

  val linkName = "BenchRsp"
  val probe = TestProbe()
  val routee = new ActorRefRoutee(probe.ref)
  val stats = TestProbe()
  val config = BenchmarkResponderConfig(2, 100 milliseconds, true, Some(stats.ref))
  val responder = system.actorOf(BenchmarkResponder.props(linkName, routee, config))

  "BenchmarkResponder" should {
    "register with proxy" in {
      val ci = ConnectionInfo(linkName + "0" * 44, linkName, false, true, None, "1.1.2", List("json"))
      probe.expectMsg(ConnectEndpoint(responder, ci))
    }
    "handle Subscribe request" in {
      val req = SubscribeRequest(11, SubscriptionPath("/data1", 101))
      val env = OutRequestEnvelope(List(req))
      probe.send(responder, env)
      probe.expectMsg(ResponseMessage(1, None, List(DSAResponse(11, Some(StreamState.Closed)))))
    }
    "handle Invoke(incCounter) and send notification" in {
      val req = InvokeRequest(12, "/data1/incCounter")
      val env = OutRequestEnvelope(List(req))
      probe.send(responder, env)
      inside(probe.receiveOne(5 seconds)) {
        case ResponseMessage(2, None, List(
          DSAResponse(12, Some(StreamState.Closed), _, _, _),
          DSAResponse(0, Some(StreamState.Open), Some(row :: Nil), _, _))) =>
          row.asInstanceOf[MapValue].value("sid") mustBe (101: NumericValue)
          row.asInstanceOf[DSAValue.MapValue].value("value") mustBe (1: NumericValue)
      }
    }
    "handle Invoke(resetCounter) and send notification" in {
      val req = InvokeRequest(13, "/data1/resetCounter")
      val env = OutRequestEnvelope(List(req))
      probe.send(responder, env)
      inside(probe.receiveOne(5 seconds)) {
        case ResponseMessage(3, None, List(
          DSAResponse(13, Some(StreamState.Closed), _, _, _),
          DSAResponse(0, Some(StreamState.Open), Some(row :: Nil), _, _))) =>
          row.asInstanceOf[MapValue].value("sid") mustBe (101: NumericValue)
          row.asInstanceOf[DSAValue.MapValue].value("value") mustBe (0: NumericValue)
      }
    }
    "emit statistics" in {
      val records = stats.receiveWhile(500 milliseconds) {
        case RspStatsSample(_, _, _, _) => 1
      }
      records.sum must be > 2
    }
    "handle Unsubscribe request" in {
      val req = UnsubscribeRequest(14, List(101))
      val env = OutRequestEnvelope(List(req))
      probe.send(responder, env)
      probe.expectMsg(ResponseMessage(4, None, List(DSAResponse(14, Some(StreamState.Closed)))))
    }
  }
}