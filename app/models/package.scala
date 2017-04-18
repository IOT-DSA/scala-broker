import models.{ RequestEnvelope, ResponseEnvelope }
import play.api.libs.json.Json

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
}