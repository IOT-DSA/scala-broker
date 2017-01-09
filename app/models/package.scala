import models.ConnectionRequest
import play.api.libs.json.Json

/**
 * Types and utility methods for models.
 */
package object models {

  implicit val ConnectionRequestReads = Json.reads[ConnectionRequest]

}