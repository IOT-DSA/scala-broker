import play.api.libs.json.Json

/**
 * Types and utility methods for models.
 */
package object controllers {

  implicit val ConnectionRequestReads = Json.reads[ConnectionRequest]

}