package models.rpc

import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatestplus.play.PlaySpec

import play.api.libs.json._
import play.api.libs.json.Json._
import collection.immutable.{ List => ScalaList }

/**
 * DSAMessage test suite.
 */
class DSAMessageSpec extends PlaySpec with GeneratorDrivenPropertyChecks {
  import DSAMethod._
  import StreamState._

  val Rid = 123
  val Path = "/top/down"

  "EmptyMessage" should {
    "serialize empty message to JSON" in {
      val json = Json.toJson(EmptyMessage)
      json mustBe Json.obj()
      json.as[DSAMessage] mustBe EmptyMessage
    }
  }

  "AllowedMessage" should {
    "serialize messages to JSON" in {
      val msg = AllowedMessage(true, 1234)
      val json = Json.toJson(msg)
      json mustBe Json.obj("allowed" -> true, "salt" -> 1234)
      json.as[DSAMessage] mustBe msg
    }
  }

  "PingMessage" should {
    "serialize messages without ack to JSON" in {
      val msg = PingMessage(10)
      val json = Json.toJson(msg)
      json mustBe Json.obj("msg" -> 10)
      json.as[DSAMessage] mustBe msg
    }
    "serialize messages with ack to JSON" in {
      val msg = PingMessage(1, Some(5))
      val json = Json.toJson(msg)
      json mustBe Json.obj("msg" -> 1, "ack" -> 5)
      json.as[DSAMessage] mustBe msg
    }
  }

  "PongMessage" should {
    "serialize messages to JSON" in {
      val msg = PongMessage(10)
      val json = Json.toJson(msg)
      json mustBe Json.obj("ack" -> 10)
      json.as[DSAMessage] mustBe msg
    }
  }

  "RequestMessage" should {
    "serialize multiple requests to JSON" in {
      val msg = RequestMessage(101, Some(20), ScalaList(ListRequest(Rid, Path), CloseRequest(Rid)))
      val json = Json.toJson(msg)
      json mustBe Json.obj("msg" -> 101, "ack" -> 20, "requests" -> Json.arr(baseJsonWithPath(List), baseJson(Close)))
      json.as[DSAMessage] mustBe msg
    }

    "write single request in string" in {
      val msg = RequestMessage(101, Some(20), ScalaList(CloseRequest(Rid)))
      msg.toString mustBe s"RequestMessage(101,Some(20),List(CloseRequest($Rid)))"
    }

    "truncate to head multiple requests in string" in {
      val msg = RequestMessage(101, Some(20), ScalaList(ListRequest(Rid, Path), ListRequest(Rid + 1, Path),
                                                        CloseRequest(Rid + 1), CloseRequest(Rid)))
      msg.toString mustBe s"RequestMessage(101,Some(20),List(ListRequest($Rid,$Path),...3 more))"
    }

  }

  "ResponseMessage" should {
    "serialize multiple responses to JSON" in {
      val msg = ResponseMessage(101, Some(20), ScalaList(DSAResponse(10, Some(Closed)), DSAResponse(11, Some(Open))))
      val json = Json.toJson(msg)
      json mustBe Json.obj("msg" -> 101, "ack" -> 20, "responses" ->
        Json.arr(Json.obj("rid" -> 10, "stream" -> "closed"), Json.obj("rid" -> 11, "stream" -> "open")))
      json.as[DSAMessage] mustBe msg
    }

    "write single request in string" in {
      val msg = ResponseMessage(101, Some(20), ScalaList(DSAResponse(10, Some(Closed))))
      msg.toString mustBe s"ResponseMessage(101,Some(20),List(DSAResponse(10,Some(closed),None,None,None)))"
    }

    "truncate to head multiple requests in string" in {
      val msg = ResponseMessage(101, Some(20), ScalaList(DSAResponse(10, Some(Closed)), DSAResponse(11, Some(Closed))))
      msg.toString mustBe s"ResponseMessage(101,Some(20),List(DSAResponse(10,Some(closed),None,None,None),...1 more))"
    }
  }

  private def baseJson(method: DSAMethod) = Json.obj("rid" -> Rid, "method" -> method)

  private def baseJsonWithPath(method: DSAMethod) = baseJson(method) + ("path" -> Json.toJson(Path))
}