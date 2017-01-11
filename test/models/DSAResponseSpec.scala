package models

import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatestplus.play.PlaySpec

import play.api.libs.json._
import play.api.libs.json.Json._
import collection.immutable.{ List => ScalaList }

/**
 * DSAResponse test suite.
 */
class DSAResponseSpec extends PlaySpec with GeneratorDrivenPropertyChecks {
  import DSAValue._
  import StreamState._

  val Rid = 123

  "DSAError" should {
    "serialize to JSON with optional fields missing" in testJson(DSAError(), Json.obj())
    "serialize to JSON with optional fields present" in testJson(DSAError(Some("permission denied"),
      Some("permissionDenied"), Some("request"), Some("/connection/dslink1"),
      Some("user not allowed to access data in '/connection/dslink1'")),
      Json.obj("msg" -> "permission denied", "type" -> "permissionDenied",
        "phase" -> "request", "path" -> "/connection/dslink1",
        "detail" -> "user not allowed to access data in '/connection/dslink1'"))
  }

  "ColumnInfo" should {
    "serialize to JSON" in testJson(ColumnInfo("ts", "time"), Json.obj("name" -> "ts", "type" -> "time"))
  }

  "DSAResponse" should {
    "serialize to JSON with optional fields missing" in testJson(DSAResponse(Rid, Closed),
      Json.obj("rid" -> Rid, "stream" -> "closed"))
    "serialize to JSON with columns" in {
      val columns = List(ColumnInfo("ts", "time"), ColumnInfo("value", "number"))
      val rsp = DSAResponse(Rid, Open, None, Some(columns))
      testJson(rsp, Json.obj("rid" -> Rid, "stream" -> "open", "columns" -> Json.arr(
        Json.obj("name" -> "ts", "type" -> "time"),
        Json.obj("name" -> "value", "type" -> "number"))))
    }
    "serialize to JSON with row list updates" in {
      val updates = List(ArrayValue(List("a", 1)), ArrayValue(List("b", MapValue(Map("c" -> 3)))))
      val rsp = DSAResponse(Rid, Initialize, Some(updates))
      testJson(rsp, Json.obj("rid" -> Rid, "stream" -> "initialize", "updates" ->
        Json.arr(Json.arr("a", 1), Json.arr("b", Json.obj("c" -> 3)))))
    }
    "serialize to JSON with key:value updates" in {
      val updates = List(MapValue(Map("a" -> 1, "b" -> 2)), MapValue(Map("c" -> 3, "d" -> 4)))
      val rsp = DSAResponse(Rid, Initialize, Some(updates))
      testJson(rsp, Json.obj("rid" -> Rid, "stream" -> "initialize", "updates" ->
        Json.arr(Json.obj("a" -> 1, "b" -> 2), Json.obj("c" -> 3, "d" -> 4))))
    }
    "serialize to JSON with error" in {
      val error = DSAError(Some("msg"), Some("type"), Some("phase"), Some("path"), Some("detail"))
      val rsp = DSAResponse(Rid, Initialize, None, None, Some(error))
      testJson(rsp, Json.obj("rid" -> Rid, "stream" -> "initialize", "error" ->
        Json.obj("msg" -> "msg", "type" -> "type", "phase" -> "phase", "path" -> "path", "detail" -> "detail")))
    }
  }

  "ResponseMessage" should {
    "serialize empty messages to JSON" in {
      val msg = ResponseMessage(10)
      val json = Json.toJson(msg)
      json mustBe Json.obj("msg" -> 10)
    }
    "serialize multiple responses to JSON" in {
      val msg = ResponseMessage(101, Some(20), Some(ScalaList(DSAResponse(10, Closed), DSAResponse(11, Open))))
      val json = Json.toJson(msg)
      json mustBe Json.obj("msg" -> 101, "ack" -> 20, "responses" ->
        Json.arr(Json.obj("rid" -> 10, "stream" -> "closed"), Json.obj("rid" -> 11, "stream" -> "open")))
      json.as[ResponseMessage] mustBe msg
    }
  }

  private def testJson[T: Reads: Writes](req: T, js: JsValue) = {
    val json = Json.toJson(req)
    json mustBe js
    json.as[T] mustBe req
  }
}