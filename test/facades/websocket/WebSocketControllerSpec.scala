package facades.websocket

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest

import play.api.libs.json.JsValue.jsValueToJsLookup
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

/**
 * Tests WebSocket connection controller.
 */
class WebSocketControllerSpec extends PlaySpec with GuiceOneAppPerTest {

  "WebSocketController /conn" should {

    val connReq = ConnectionRequest("", true, true, None, "", None, true)
    val request = FakeRequest("POST", "/conn?dsId=Shell-EX8oEoINlQFdp1WscgoQAjeFZz4shQKERE7fdm1rcWg").withBody(connReq)

    "render the connection response from the application" in {
      val controller = app.injector.instanceOf[WebSocketController]
      val conn = controller.dslinkHandshake(request)

      status(conn) mustBe OK
      contentType(conn) mustBe Some("application/json")

      val json = contentAsJson(conn)
      (json \ "format").toOption.value mustBe Json.toJson("json")
      (json \ "wsUri").toOption.value mustBe Json.toJson("/ws")
      (json \ "path").toOption.value mustBe Json.toJson("/downstream/Shell")
    }
  }
}