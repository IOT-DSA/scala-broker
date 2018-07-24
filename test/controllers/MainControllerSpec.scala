package controllers

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest

import play.api.Application
import play.api.cache.SyncCacheApi
import play.api.test.{FakeRequest, FakeHeaders}
import play.api.mvc.AnyContentAsEmpty
import play.api.http.HeaderNames
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

    "render the index page via https" in {
      val controller = app.injector.instanceOf[MainController]
      val home = controller.index().apply(FakeRequest(
        method = "GET"
        , uri = "/"
        , headers = FakeHeaders(Seq(HeaderNames.HOST -> "localhost"))
        , body = AnyContentAsEmpty
        , secure = true
      ))

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
}