package controllers

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.util.Timeout
import play.api.Logger
import play.api.libs.json.{ JsError, JsValue, Json, Reads, Writes }
import play.api.mvc.{ AbstractController, ControllerComponents, Result }

/**
 * Base abstract class for application controllers, provides various helper methods.
 */
abstract class BasicController(cc: ControllerComponents) extends AbstractController(cc) {

  protected val log = Logger(getClass)

  implicit protected val ec = cc.executionContext

  implicit protected val timeout = Timeout(5 seconds)

  /**
   * Validates the JSON and extracts a request message.
   */
  protected def validateJson[A: Reads] = parse.tolerantJson.validate { js =>
    js.validate[A].asEither.left.map { e =>
      log.error(s"Cannot parse connection request JSON: $js. Error info: ${JsError.toJson(e)}")
      BadRequest(JsError.toJson(e))
    }
  }

  /**
   * Converts a Future[T] into a future HTTP Result (to be used with Action.async...)
   * by mapping T into a JSON.
   */
  implicit protected def fModel2jsonResut[T](f: Future[T])(implicit w: Writes[T]): Future[Result] =
    f map (model2jsonResult(_))

  /**
   * Converts an instance of T into an HTTP Result by converting it into a JSON.
   */
  implicit protected def model2jsonResult[T](x: T)(implicit w: Writes[T]): Result =
    json2result(w.writes(x))

  /**
   * Converts a JSON value into a (pretty-printed) HTTP Result.
   */
  implicit protected def json2result(json: JsValue, pretty: Boolean = true): Result =
    if (pretty)
      Ok(Json.prettyPrint(json)).as(JSON)
    else
      Ok(json)
}