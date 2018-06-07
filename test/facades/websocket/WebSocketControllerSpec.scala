package facades.websocket

import models.handshake.{LocalKeys, RemoteKey}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.libs.json.JsValue.jsValueToJsLookup
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Tests WebSocket connection controller.
 */
class WebSocketControllerSpec extends PlaySpec with GuiceOneAppPerTest {

  import models.rpc.DSAMessageSerrializationFormat._
  implicit def materializer = app.materializer

  "WebSocketController /conn" should {

    "render the connection response from the application" in {
      val clientKeys = LocalKeys.generate

      val connReq = ConnectionRequest(clientKeys.encodedPublicKey, true, true, None, "", Option(List(MSGJSON, MSGPACK)), true)
      val request = FakeRequest("POST", "/conn?dsId=Shell-EX8oEoINlQFdp1WscgoQAjeFZz4shQKERE7fdm1rcWg").withBody(connReq)

      val controller = app.injector.instanceOf[WebSocketController]
      val conn = controller.dslinkHandshake(request)

      status(conn) mustBe OK
      contentType(conn) mustBe Some("application/json")

      val json = contentAsJson(conn)
      (json \ "format").toOption.value mustBe Json.toJson(MSGPACK)
      (json \ "wsUri").toOption.value mustBe Json.toJson("/ws")
      (json \ "path").toOption.value mustBe Json.toJson("/downstream/Shell")
    }
  }

  "WebSocketController /ws" should {

    "render the ws connection response from the application" in {
      val clientKeys = LocalKeys.generate

      val controller = app.injector.instanceOf[WebSocketController]

      val connReq = ConnectionRequest(clientKeys.encodedPublicKey
        , true, true, None, "", Option(List(MSGJSON, MSGPACK)), true)

      // conn
      val request = FakeRequest("POST", "/conn?dsId=Shell-EX8oEoINlQFdp1WscgoQAjeFZz4shQKERE7fdm1rcWg").withBody(connReq)
      val conn1 = controller.dslinkHandshake(request)
      val res1 = Await.result(conn1, 3 seconds)

      val r1 = res1.body.consumeData.map(_.utf8String)
      val r2 = Await.result(r1, 3 seconds)

      val res1Jsone = Json.parse(r2)

      val tempKey = (res1Jsone \ "tempKey").get.as[String]
      val salt = (res1Jsone \ "salt").get.as[String].getBytes("UTF-8")

      // ws
      val remoteKey = RemoteKey.generate(clientKeys, tempKey)
      val sharedSecret = remoteKey.sharedSecret

      val auth = ""//LocalKeys.saltSharedSecret(salt, sharedSecret)
      val format = MSGJSON

      val reqUri = s"/ws?dsId=Shell-EX8oEoINlQFdp1WscgoQAjeFZz4shQKERE7fdm1rcWg&auth=$auth&format=$format"
      val requestWs = FakeRequest("GET", reqUri)

      val conn2 = controller.dslinkWSConnect(requestWs)
      val result = Await.result(conn2, 3 seconds)

      // Bellow line was used before, when request had not been completed well. Right now it does OK.
      // TODO: Uncomment following line and change it to proper assert condition for "OK" case.
//      result.left.get.header.status mustBe FORBIDDEN
    }
  }

  // TODO: Add additional tests when publicKey is absent and for wrong auth
}
