package models.sdk

import akka.stream.scaladsl.Source
import models.akka.AbstractActorSpec
import models.api.DSAValueType.{DSANumber, DSAString}
import models.rpc.DSAValue._

/**
  * NodeAction test suite.
  */
class NodeActionSpec extends AbstractActorSpec {

  import ActionContext._

  "Param" should {
    "implement withEditor" in {
      Param("abc", DSAString).withEditor("daterange").editor.value mustBe "daterange"
    }
    "implement writableAs" in {
      Param("abc", DSAString).writableAs("config").writable.value mustBe "config"
    }
    "implement asOutput" in {
      Param("abc", DSAString).asOutput().output mustBe true
    }
    "implement toMapValue" in {
      Param("abc", DSANumber).toMapValue mustBe obj("name" -> "abc", "type" -> DSANumber)
      Param("abc", DSANumber, Some("date")).toMapValue mustBe obj("name" -> "abc", "type" -> DSANumber,
        "editor" -> "date")
      Param("abc", DSANumber, Some("date"), Some("config")).toMapValue mustBe obj("name" -> "abc",
        "type" -> DSANumber, "editor" -> "date", "writable" -> "config")
      Param("abc", DSANumber, Some("date"), Some("config"), true).toMapValue mustBe obj("name" -> "abc",
        "type" -> DSANumber, "editor" -> "date", "writable" -> "config", "output" -> true)
    }
  }

  "ActionContext" should {
    val args: DSAMap = Map("a" -> 123, "b" -> 4.5, "c" -> true, "d" -> Array(1.toByte, 2.toByte),
      "e" -> array("x", "y"), "f" -> obj("m" -> 1, "n" -> 2), "g" -> "abc")
    val ctx = ActionContext(null, args)
    "extract numeric arguments" in {
      ctx.as[Int]("a") mustBe 123
      ctx.get[Long]("a") mustBe Some(123)
      ctx.get[Double]("aa") mustBe empty
      ctx.as[Double]("b") mustBe 4.5
      ctx.as[Int]("b") mustBe 4
    }
    "extract string arguments" in {
      ctx.as[String]("g") mustBe "abc"
    }
    "extract boolean arguments" in {
      ctx.get[Boolean]("c") mustBe Some(true)
    }
    "extract binary arguments" in {
      ctx.as[Binary]("d") mustBe Array(1.toByte, 2.toByte)
    }
    "extract array arguments" in {
      ctx.get[DSAArray]("e") mustBe Some(array("x", "y").value)
    }
    "extract map arguments" in {
      ctx.as[DSAMap]("f") mustBe obj("m" -> 1, "n" -> 2).value
    }
    "fail for missing argument name" in {
      a[NoSuchElementException] must be thrownBy ctx.as[Int]("aa")
    }
    "fail for wrong argument type" in {
      val thrown = the[IllegalArgumentException] thrownBy ctx.as[Int]("e")
      thrown.getMessage mustBe "Wrong argument type, must be NumericValue"
    }
  }

  "ActionResult" should {
    "support scalar results" in {
      ActionResult(5) mustBe 'closed
    }
    "support stream results" in {
      val stream = Source(array(1, 2, 3).value.toList)
      ActionResult(0, Some(stream)) must not be 'closed
    }
  }
}
