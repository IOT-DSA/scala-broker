package controllers

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest

import javax.inject.Singleton
import play.api.test.FakeRequest
import play.api.test.Helpers.{ OK, contentType, defaultAwaitTimeout, status }

/**
 * Tests metric controller functions.
 */
class MetricControllerSpec extends PlaySpec with GuiceOneAppPerTest {

  "MetricController" should {
    "render member events json" in {
      val controller = app.injector.instanceOf[MetricController]
      val events = controller.memberEvents(None, None, None, None).apply(FakeRequest())

      status(events) mustBe OK
      contentType(events) mustBe Some("application/json")
    }
    "render connection events json" in {
      val controller = app.injector.instanceOf[MetricController]
      val events = controller.connectionEvents(None, None, None).apply(FakeRequest())

      status(events) mustBe OK
      contentType(events) mustBe Some("application/json")
    }
    "render session events json" in {
      val controller = app.injector.instanceOf[MetricController]
      val events = controller.sessionEvents(None, None, None).apply(FakeRequest())

      status(events) mustBe OK
      contentType(events) mustBe Some("application/json")
    }
    "render request stats by link json" in {
      val controller = app.injector.instanceOf[MetricController]
      val events = controller.requestStatsByLink(None, None).apply(FakeRequest())

      status(events) mustBe OK
      contentType(events) mustBe Some("application/json")
    }
    "render request stats by method json" in {
      val controller = app.injector.instanceOf[MetricController]
      val events = controller.requestStatsByMethod(None, None).apply(FakeRequest())

      status(events) mustBe OK
      contentType(events) mustBe Some("application/json")
    }
    "render response stats by link json" in {
      val controller = app.injector.instanceOf[MetricController]
      val events = controller.responseStatsByLink(None, None).apply(FakeRequest())

      status(events) mustBe OK
      contentType(events) mustBe Some("application/json")
    }
  }
}