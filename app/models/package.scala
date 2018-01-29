import play.api.libs.json.Json
import models.rpc.DSAMessage

package object models {

  /**
   * RequestEnvelope <-> JSON
   */
  implicit val RequestEnvelopeFormat = Json.format[RequestEnvelope]

  /**
   * RequestEnvelope <-> JSON
   */
  implicit val ResponseEnvelopeFormat = Json.format[ResponseEnvelope]

  /* Special actions */
  val AddAttributeAction = "addAttribute"
  val SetValueAction = "setValue"

  /**
   * Splits a path into a (node, attribute/config) pair. If the path does not represents
   * an attribute or config, the second element of the returned tuple will be None.
   */
  def splitPath(path: String): (String, Option[String]) = path.split("/").lastOption collect {
    case s if s.startsWith("@") || s.startsWith("$") => (path.dropRight(s.size + 1), Some(s))
  } getOrElse Tuple2(path, None)

  /**
   * Returns `true` if the path represents an attribute.
   */
  def isAttribute(path: String) = path matches ".*/@[^/@]+"

  /**
   * Returns `true` if the path represents a config.
   */
  def isConfig(path: String) = path matches ".*/\\$\\$?[^/\\$]+"

  /**
   * Helper class providing a simple syntax to add side effects to the returned value:
   *
   * {{{
   * def square(x: Int) = {
   *            x * x
   * } having (r => println "returned: " + r)
   * }}}
   *
   * or simplified
   *
   * {{{
   * def square(x: Int) = (x * x) having println
   * }}}
   */
  final implicit class Having[A](val result: A) extends AnyVal {
    def having(body: A => Unit): A = {
      body(result)
      result
    }
    def having(body: => Unit): A = {
      body
      result
    }
  }

  /**
   * A helper for converting a Boolean to Option. Eg. `condition.option("111")`
   */
  implicit class RichBoolean(val b: Boolean) extends AnyVal {
    final def option[A](a: => A): Option[A] = if (b) Some(a) else None
  }

  /**
   * Outputs the message either as raw JSON or as Scala object, according to
   * `broker.logging.show.ws.payload` config.
   */
  def formatMessage(msg: DSAMessage) = if (Settings.Logging.ShowWebSocketPayload)
    play.api.libs.json.Json.toJson(msg).toString
  else
    msg.toString  
}