package controllers

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest

import javax.inject.Singleton
import play.api.Application
import play.api.cache.SyncCacheApi
import play.api.libs.json.JsValue.jsValueToJsLookup
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

/**
 * Tests main controller functions.
 */
class MainControllerSpec extends PlaySpec with GuiceOneAppPerTest {

  implicit def configuration = app.configuration
  implicit def actorSystem = app.actorSystem
  implicit def materializer = app.materializer

  val cacheApiCache = Application.instanceCache[SyncCacheApi]
  implicit def getCache(implicit app: Application) = cacheApiCache(app)

  "MainController /" should {

    "render the index page from the application" in {
      val controller = app.injector.instanceOf[MainController]
      val home = controller.index().apply(FakeRequest())

      status(home) mustBe OK
      contentType(home) mustBe Some("text/html")
      contentAsString(home) must include("DSABroker")
    }

    "render the index page from the router" in {
      val request = FakeRequest(GET, "/").withHeaders("Host" -> "localhost")
      val home = route(app, request).get

      status(home) mustBe OK
      contentType(home) mustBe Some("text/html")
      contentAsString(home) must include("DSABroker")
    }
  }

  "MainController /conn" should {

    val connReq = ConnectionRequest("", true, true, None, "", None, true)
    val request = FakeRequest("POST", "/conn?dsId=Shell-EX8oEoINlQFdp1WscgoQAjeFZz4shQKERE7fdm1rcWg").withBody(connReq)

    "render the connection response from the application" in {
      val controller = app.injector.instanceOf[MainController]
      val conn = controller.conn().apply(request)

      status(conn) mustBe OK
      contentType(conn) mustBe Some("application/json")

      val json = contentAsJson(conn)
      (json \ "format").toOption.value mustBe Json.toJson("json")
      (json \ "wsUri").toOption.value mustBe Json.toJson("/ws")
      (json \ "path").toOption.value mustBe Json.toJson("/downstream/Shell")
    }
  }
}