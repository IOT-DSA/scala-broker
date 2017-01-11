package models

import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatestplus.play.PlaySpec

import play.api.libs.json._
import play.api.libs.json.Json._
import collection.immutable.{ List => ScalaList }

/**
 * DSARequest test suite.
 */
class DSARequestSpec extends PlaySpec with GeneratorDrivenPropertyChecks {
  import DSAValue._
  import DSAMethod._

  val Rid = 123
  val Path = "/top/down"

  "ListRequest" should {
    "serialize to JSON" in testJson(ListRequest(Rid, Path), baseJsonWithPath(List))
  }

  "SetRequest" should {
    "serialize simple values to JSON" in forAll { (a: Int, b: Boolean, c: String, d: Double) =>
      testJson(SetRequest(Rid, Path, a, None), baseJsonWithPath(Set) ++ Json.obj("value" -> a))
      testJson(SetRequest(Rid, Path, b, Some("write")), baseJsonWithPath(Set) ++ Json.obj("value" -> b, "permit" -> "write"))
      testJson(SetRequest(Rid, Path, c), baseJsonWithPath(Set) ++ Json.obj("value" -> c))
      testJson(SetRequest(Rid, Path, d, None), baseJsonWithPath(Set) ++ Json.obj("value" -> d))
    }
    "serialize flat arrays to JSON" in forAll { (a: Int, b: Long, c: String) =>
      val arr = ArrayValue(ScalaList(a, b, c))
      testJson(SetRequest(Rid, Path, arr), baseJsonWithPath(Set) ++ Json.obj("value" -> arr))
    }
    "serialize flat maps to JSON" in forAll { (a: Int, b: Boolean, c: String) =>
      val obj = MapValue(Map("a" -> a, "b" -> b, "c" -> c))
      testJson(SetRequest(Rid, Path, obj), baseJsonWithPath(Set) ++ Json.obj("value" -> obj))
    }
    "serialize nested collections to JSON" in forAll { (a: String, b: Boolean, c: Int, d: Double) =>
      val cd = ArrayValue(ScalaList(c, d))
      val bcd = MapValue(Map("b" -> b, "cd" -> cd))
      val obj = MapValue(Map("bcd" -> bcd, "a" -> a))
      testJson(SetRequest(Rid, Path, obj), baseJsonWithPath(Set) ++ Json.obj("value" -> obj))
    }
  }

  "RemoveRequest" should {
    "serialize to JSON" in testJson(RemoveRequest(Rid, Path), baseJsonWithPath(Remove))
  }

  "InvokeRequest" should {
    "serialize without parameters to JSON" in {
      testJson(InvokeRequest(Rid, Path), baseJsonWithPath(Invoke))
    }
    "serialize with simple parameters to JSON" in { (a: Int, b: Boolean, c: String) =>
      val params: DSAMap = Map("a" -> a, "b" -> b, "c" -> c)
      testJson(InvokeRequest(Rid, Path, params), baseJsonWithPath(Invoke) ++ Json.obj("params" -> params))
    }
    "serialize with complex parameters to JSON" in forAll { (a: String, b: Boolean, c: Int, d: Double) =>
      val cd = ArrayValue(ScalaList(c, d))
      val bcd = MapValue(Map("b" -> b, "cd" -> cd))
      testJson(InvokeRequest(Rid, Path, Map("bcd" -> bcd, "a" -> a)),
        baseJsonWithPath(Invoke) ++ Json.obj("params" ->
          Json.obj("a" -> a, "bcd" -> Json.obj("b" -> b, "cd" -> Json.arr(c, d)))))
    }
  }

  "SubscribeRequest" should {
    "support `path` property for single value lists" in {
      val path = SubscriptionPath("pathX", 2)
      val req = SubscribeRequest(Rid, path)
      req.path mustBe path
    }
    "split multiple-path request into multiple single-path requests" in {
      val paths = ScalaList(SubscriptionPath("path1", 1), SubscriptionPath("path2", 2, Some(2)),
        SubscriptionPath("path3", 3, Some(3)))
      val req = SubscribeRequest(Rid, paths)
      req.split must contain only (SubscribeRequest(Rid, paths(0)), SubscribeRequest(Rid, paths(1)),
        SubscribeRequest(Rid, paths(2)))
    }
    "serialize to JSON" in {
      val paths = ScalaList(SubscriptionPath("path1", 1),
        SubscriptionPath("path2", 2, Some(2)), SubscriptionPath("path3", 3, Some(3)))
      testJson(SubscribeRequest(Rid, paths), baseJson(Subscribe) ++ Json.obj("paths" -> Json.arr(
        Json.obj("path" -> "path1", "sid" -> 1),
        Json.obj("path" -> "path2", "sid" -> 2, "qos" -> 2),
        Json.obj("path" -> "path3", "sid" -> 3, "qos" -> 3))))
    }
  }

  "UnsubscribeRequest" should {
    "serialize to JSON" in testJson(UnsubscribeRequest(Rid, ScalaList(1, 2, 3)),
      baseJson(Unsubscribe) ++ Json.obj("sids" -> Json.arr(1, 2, 3)))
  }

  "CloseRequest" should {
    "serialize to JSON" in testJson(CloseRequest(Rid), baseJson(Close))
  }

  "RequestMessage" should {
    "serialize ping messages to JSON" in {
      val msg = PingMessage(10)
      val json = Json.toJson(msg)
      json mustBe Json.obj("msg" -> 10)
    }
    "serialize multiple requests to JSON" in {
      val msg = RequestMessage(101, Some(20), ScalaList(ListRequest(Rid, Path), CloseRequest(Rid)))
      val json = Json.toJson(msg)
      json mustBe Json.obj("msg" -> 101, "ack" -> 20, "requests" -> Json.arr(baseJsonWithPath(List), baseJson(Close)))
      json.as[DSAMessage] mustBe msg
    }
  }

  private def testJson(req: DSARequest, js: JsValue) = {
    val json = Json.toJson(req)
    json mustBe js
    json.as[DSARequest] mustBe req
  }

  private def baseJson(method: DSAMethod) = Json.obj("rid" -> Rid, "method" -> method)

  private def baseJsonWithPath(method: DSAMethod) = baseJson(method) + ("path" -> Json.toJson(Path))
}