package org.dsa.iot.broker

import org.json4s._
import org.json4s.JsonDSL.WithBigDecimal._

/**
 * DSARequest testing suite.
 */
class DSARequestSpec extends AbstractSpec {
  import DSAValue._

  val Rid = 123
  val Path = "/top/down"

  "ListRequest" should {
    "serialize to JSON" in {
      val req = ListRequest(Rid, Path)
      req.toJson shouldBe baseJsonWithPath(ListRequest.MethodName)
      DSARequest.fromJson(req.toJson) shouldBe req
    }
  }

  "SetRequest" should {
    "serialize requests with simple values to JSON" in forAll { (a: Int, b: Boolean, c: String) =>
      val req1 = SetRequest(Rid, Path, a, None)
      req1.toJson shouldBe baseJsonWithPath(SetRequest.MethodName) ~ ("value" -> a) ~ noPermit
      DSARequest.fromJson(req1.toJson) shouldBe req1
      val req2 = SetRequest(Rid, Path, b, Some("write"))
      req2.toJson shouldBe baseJsonWithPath(SetRequest.MethodName) ~ ("value" -> b) ~ ("permit" -> "write")
      DSARequest.fromJson(req2.toJson) shouldBe req2
      val req3 = SetRequest(Rid, Path, c)
      req3.toJson shouldBe baseJsonWithPath(SetRequest.MethodName) ~ ("value" -> c) ~ noPermit
      DSARequest.fromJson(req3.toJson) shouldBe req3
    }
    "serialize requests with flat arrays to JSON" in forAll { (a: Int, b: Long, c: String) =>
      val arr = ArrayValue(List(a, b, c))
      val req = SetRequest(Rid, Path, arr)
      req.toJson shouldBe baseJsonWithPath(SetRequest.MethodName) ~ ("value" -> arr.toJson) ~ noPermit
      DSARequest.fromJson(req.toJson) shouldBe req
    }
    "serialize requests with flat maps to JSON" in forAll { (a: Int, b: Boolean, c: String) =>
      val obj = MapValue(Map("a" -> a, "b" -> b, "c" -> c))
      val req = SetRequest(Rid, Path, obj)
      req.toJson shouldBe baseJsonWithPath(SetRequest.MethodName) ~ ("value" -> obj.toJson) ~ noPermit
      DSARequest.fromJson(req.toJson) shouldBe req
    }
    "serialize requests with nested collections to JSON" in forAll { (a: String, b: Boolean, c: Int, d: Double) =>
      val cd = ArrayValue(List(c, d))
      val bcd = MapValue(Map("b" -> b, "cd" -> cd))
      val obj = MapValue(Map("bcd" -> bcd, "a" -> a))
      val req = SetRequest(Rid, Path, obj)
      req.toJson shouldBe baseJsonWithPath(SetRequest.MethodName) ~ ("value" -> obj.toJson) ~ noPermit
      DSARequest.fromJson(req.toJson) shouldBe req
    }
  }

  "RemoveRequest" should {
    "serialize requests to JSON" in {
      val req = RemoveRequest(Rid, Path)
      req.toJson shouldBe baseJsonWithPath(RemoveRequest.MethodName)
      DSARequest.fromJson(req.toJson) shouldBe req
    }
  }

  "InvokeRequest" should {
    "serialize requests without parameters to JSON" in {
      val req = InvokeRequest(Rid, Path)
      req.toJson shouldBe baseJsonWithPath(InvokeRequest.MethodName) ~ noParams ~ noPermit
      DSARequest.fromJson(req.toJson) shouldBe req
    }
    "serialize requests with simple parameters to JSON" in { (a: Int, b: Boolean, c: String) =>
      val req = InvokeRequest(Rid, Path, Map("a" -> a, "b" -> b, "c" -> c))
      req.toJson shouldBe baseJsonWithPath(InvokeRequest.MethodName) ~ ("params" -> req.params.toJson) ~ noPermit
      DSARequest.fromJson(req.toJson) shouldBe req
    }
    "serialize requests with complex parameters to JSON" in forAll { (a: String, b: Boolean, c: Int, d: Double) =>
      val cd = ArrayValue(List(c, d))
      val bcd = MapValue(Map("b" -> b, "cd" -> cd))
      val req = InvokeRequest(Rid, Path, Map("bcd" -> bcd, "a" -> a))
      req.toJson shouldBe {
        val jCD: JArray = List(c: JValue, d: JValue)
        val jBCD = ("b" -> b) ~ ("cd" -> jCD)
        val params = ("bcd" -> jBCD) ~ ("a" -> a)
        baseJsonWithPath(InvokeRequest.MethodName) ~ ("params" -> params) ~ noPermit
      }
      DSARequest.fromJson(req.toJson) shouldBe req
    }
  }

  "SubscribeRequest" should {
    "support `path` property for single value lists" in {
      val path = SubscriptionPath("pathX", 2)
      val req = SubscribeRequest(Rid, path)
      req.path shouldBe path
    }
    "split multiple-path request into multiple single-path requests" in {
      val paths = List(SubscriptionPath("path1", 1), SubscriptionPath("path2", 2, Some(2)),
        SubscriptionPath("path3", 3, Some(3)))
      val req = SubscribeRequest(Rid, paths)
      req.split should contain only (SubscribeRequest(Rid, paths(0)), SubscribeRequest(Rid, paths(1)),
        SubscribeRequest(Rid, paths(2)))
    }
    "serialize requests to JSON" in {
      val paths = List(SubscriptionPath("path1", 1), SubscriptionPath("path2", 2, Some(2)),
        SubscriptionPath("path3", 3, Some(3)))
      val req = SubscribeRequest(Rid, paths)
      req.toJson shouldBe {
        val jPaths = List(
          ("path" -> "path1") ~ ("sid" -> 1) ~ ("qos" -> JNothing),
          ("path" -> "path2") ~ ("sid" -> 2) ~ ("qos" -> 2),
          ("path" -> "path3") ~ ("sid" -> 3) ~ ("qos" -> 3))
        baseJson(SubscribeRequest.MethodName) ~ ("paths" -> jPaths)
      }
      DSARequest.fromJson(req.toJson) shouldBe req
    }
  }

  "UnsubscribeRequest" should {
    "support `sid` property for single value lists" in {
      val req = UnsubscribeRequest(Rid, 3)
      req.sid shouldBe 3
    }
    "split multiple-sid request into multiple single-sid requests" in {
      val req = UnsubscribeRequest(Rid, 4, 5, 6)
      req.split should contain only (UnsubscribeRequest(Rid, 4), UnsubscribeRequest(Rid, 5),
        UnsubscribeRequest(Rid, 6))
    }
    "serialize requests to JSON" in {
      val req = UnsubscribeRequest(Rid, List(1, 2, 3))
      req.toJson shouldBe baseJson(UnsubscribeRequest.MethodName) ~ ("sids" -> List(1, 2, 3))
      DSARequest.fromJson(req.toJson) shouldBe req
    }
  }

  "CloseRequest" should {
    "serialize requests to JSON" in {
      val req = CloseRequest(Rid)
      req.toJson shouldBe baseJson(CloseRequest.MethodName)
      DSARequest.fromJson(req.toJson) shouldBe req
    }
  }

  private def baseJson(method: String) = ("rid" -> Rid) ~ ("method" -> method)

  private def baseJsonWithPath(method: String) = baseJson(method) ~ ("path" -> Path)

  private val noPermit = ("permit" -> JNothing)

  private val noParams = ("params" -> JNothing)
}