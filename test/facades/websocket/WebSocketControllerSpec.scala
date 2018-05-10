package facades.websocket

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest

import play.api.libs.json.JsValue.jsValueToJsLookup
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

//import com.typesafe.config.ConfigFactory
//import models.Settings.rootConfig
//import play.api.libs.json.JsString
//import play.api.libs.json.{JsArray, JsValue, Json}
//import scala.collection.JavaConverters.asScalaSetConverter
//import play.api.libs.json.Json.toJsFieldJsValueWrapper


/**
 * Tests WebSocket connection controller.
 */
class WebSocketControllerSpec extends PlaySpec with GuiceOneAppPerTest {

  "WebSocketController /conn" should {

//    "render the connection response from the application" in {
//    val connReq = ConnectionRequest("", true, true, None, "", Option(List("json", "msgpack")), true)
//    val request = FakeRequest("POST", "/conn?dsId=Shell-EX8oEoINlQFdp1WscgoQAjeFZz4shQKERE7fdm1rcWg")
//      .withBody(connReq)

//      val controller = app.injector.instanceOf[WebSocketController]
//      val conn = controller.dslinkHandshake(request)
//
//      status(conn) mustBe OK
//      contentType(conn) mustBe Some("application/json")
//
//      val json = contentAsJson(conn)
//      (json \ "format").toOption.value mustBe Json.toJson("msgpack")
//      (json \ "wsUri").toOption.value mustBe Json.toJson("/ws")
//      (json \ "path").toOption.value mustBe Json.toJson("/downstream/Shell")
//    }

    "WebSocketController /ws" should {

      val connReq = ConnectionRequest("", true, true, None, "", Option(List("json", "msgpack")), true)
      val reqUri = "/ws?dsId=Shell-EX8oEoINlQFdp1WscgoQAjeFZz4shQKERE7fdm1rcWg&auth=123&format=msgpack"
      val request = FakeRequest("GET", reqUri)

      "render the ws connection response from the application" in {
        val controller = app.injector.instanceOf[WebSocketController]
        val conn = controller.dslinkWSConnect(request)

        //        status(conn) mustBe OK
        //        contentType(conn) mustBe Some("application/json")

        //        val json = contentAsJson(conn)
        //        (json \ "format").toOption.value mustBe Json.toJson("msgpack")
        //        (json \ "wsUri").toOption.value mustBe Json.toJson("/ws")
        //        (json \ "path").toOption.value mustBe Json.toJson("/downstream/Shell")
      }
    }
  }

//  "Server configuration" should {
//    val rootConfig = ConfigFactory.load
//    val BrokerName = rootConfig.getString("broker.name")
//    val cfg = rootConfig.getConfig("broker.server-config")
//
//    val l : java.util.List[String] = cfg.getStringList("format")
//    val l1 = Seq(l.toArray():_*)
//    val l2 = l1.map(s => JsString(s.asInstanceOf[String]))
//    val l3 = JsArray(l2)
//
//    println(l3)
//  }
}