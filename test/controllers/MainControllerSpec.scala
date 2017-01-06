package controllers

import org.scalatestplus.play.{ OneAppPerTest, PlaySpec }

import play.api.test.FakeRequest
import play.api.test.Helpers._

/**
 * Tests main controller functions.
 */
class MainControllerSpec extends PlaySpec with OneAppPerTest {

  implicit def configuration = app.configuration
  implicit def actorSystem = app.actorSystem
  implicit def materializer = app.materializer

  "HomeController GET" should {

    "render the index page from a new instance of controller" in {
      val controller = new MainController
      val home = controller.index().apply(FakeRequest())

      status(home) mustBe OK
      contentType(home) mustBe Some("text/html")
      contentAsString(home) must include("DSABroker")
    }

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
}