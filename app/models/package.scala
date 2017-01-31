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
}