package facades.websocket

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.libs.json.JsValue.jsValueToJsLookup
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.duration._
import scala.concurrent.Await

/**
 * Tests WebSocket connection controller.
 */
class WebSocketControllerSpec extends PlaySpec with GuiceOneAppPerTest {

  import models.rpc.DSAMessageSerrializationFormat._

  "WebSocketController /conn" should {

    "render the connection response from the application" in {
      val connReq = ConnectionRequest("", true, true, None, "", Option(List(MSGJSON, MSGPACK)), true)
      val request = FakeRequest("POST", "/conn?dsId=Shell-EX8oEoINlQFdp1WscgoQAjeFZz4shQKERE7fdm1rcWg").withBody(connReq)

      val controller = app.injector.instanceOf[WebSocketController]
      val conn = controller.dslinkHandshake(request)

      status(conn) mustBe OK
      contentType(conn) mustBe Some("application/json")

      val json = contentAsJson(conn)
      (json \ "format").toOption.value mustBe Json.toJson(MSGJSON)
      (json \ "wsUri").toOption.value mustBe Json.toJson("/ws")
      (json \ "path").toOption.value mustBe Json.toJson("/downstream/Shell")
    }
  }

  "WebSocketController /ws" should {

    "render the ws connection response from the application" in {
      val tempKey = (models.Settings.ServerConfiguration \ "tempKey").as[String]
      val salt = (models.Settings.ServerConfiguration \ "salt").as[String].getBytes("UTF-8")

      val controller = app.injector.instanceOf[WebSocketController]

      val connReq = ConnectionRequest("", true, true, None, "", Option(List(MSGJSON, MSGPACK)), true)
      val request = FakeRequest("POST", "/conn?dsId=Shell-EX8oEoINlQFdp1WscgoQAjeFZz4shQKERE7fdm1rcWg")
        .withBody(connReq)

      // conn
      // TODO: implement full cirlce unit tests for ws connection
      // val conn1 = controller.dslinkHandshake(request)

      // ws
      val auth = controller.buildAuth(tempKey, salt)
      val format = MSGPACK

      val reqUri = s"/ws?dsId=Shell-EX8oEoINlQFdp1WscgoQAjeFZz4shQKERE7fdm1rcWg&auth=$auth&format=$format"
      val requestWs = FakeRequest("GET", reqUri)

      val conn2 = controller.dslinkWSConnect(requestWs)
      val result = Await.result(conn2, 3.seconds)

      // There should be FORBIDDEN here as there is no session (Check prev. TODO)
      result.left.get.header.status mustBe FORBIDDEN
    }
  }
}
