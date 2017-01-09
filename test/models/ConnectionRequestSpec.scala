package models

import org.scalatestplus.play.PlaySpec
import play.api.libs.json._

/**
 * Test suite for ConnectionRequest.
 */
class ConnectionRequestSpec extends PlaySpec {

  "ConnectionRequest" should {
    "read from JSON (no linkData)" in {
      val str = """{
        "publicKey": "BEACGownMzthVjNFT7Ry-RPX395kPSoUqhQ",
        "isRequester": true,
        "isResponder": false,
        "version": "1.1.2",
        "formats":["msgpack","json"],
        "enableWebSocketCompression": true
        }"""
      val req = Json.parse(str).asOpt[ConnectionRequest]
      Json.parse(str).validate[ConnectionRequest] match {
        case JsSuccess(req, _) =>
          req.publicKey mustBe "BEACGownMzthVjNFT7Ry-RPX395kPSoUqhQ"
          req.isRequester mustBe true
          req.isResponder mustBe false
          req.linkData mustBe None
          req.version mustBe "1.1.2"
          req.formats mustBe List("msgpack", "json")
          req.enableWebSocketCompression mustBe true
        case JsError(errors) => fail(errors.mkString("\n"))
      }
    }
    "read from JSON (with linkData)" in {
      val str = """{
        "publicKey": "BEACGownMzthVjNFT7Ry-RPX395kPSoUqhQ",
        "isRequester": false,
        "isResponder": true,
        "linkData": {"a": 1, "b": [true, false], "c" : {"d": 3.3}},
        "version": "1.1.2",
        "formats":["msgpack","json"],
        "enableWebSocketCompression": true
        }"""
      val req = Json.parse(str).asOpt[ConnectionRequest]
      Json.parse(str).validate[ConnectionRequest] match {
        case JsSuccess(req, _) =>
          req.publicKey mustBe "BEACGownMzthVjNFT7Ry-RPX395kPSoUqhQ"
          req.isRequester mustBe false
          req.isResponder mustBe true
          req.linkData.value mustBe Json.obj("a" -> 1, "b" -> Json.arr(true, false), "c" -> Json.obj("d" -> 3.3))
          req.version mustBe "1.1.2"
          req.formats mustBe List("msgpack", "json")
          req.enableWebSocketCompression mustBe true
        case JsError(errors) => fail(errors.mkString("\n"))
      }
    }
  }
}