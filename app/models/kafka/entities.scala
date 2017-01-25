package models.kafka

import org.apache.kafka.streams.state.KeyValueStore
import models.DSARequest
import models.DSAResponse

/**
 * Envelope for Kafka request routing.
 */
case class KafkaRequestEnvelope(request: DSARequest, source: String)

/**
 * Generates an increasing number sequence per key.
 */
class IdGenerator(store: KeyValueStore[String, Integer], init: Int = 0) {

  /**
   * Returns the last generated value or `init` if it has not been set.
   */
  def get(key: String): Int = Option(store.get(key)) map (_.intValue) getOrElse init

  /**
   * Returns the last generated value or `init` if it has not been set and increments the counter.
   */
  def inc(key: String): Int = {
    val value = get(key)
    store.put(key, value + 1)
    value
  }
}

/**
 * Used in call records to store the subscribers for future responses.
 */
case class RequestOrigin(source: String, sourceId: Int)

/**
 * Encapsulates information about requests's subscribers and last received response.
 */
case class RequestRecord(targetId: Int, path: Option[String],
                         origins: Set[RequestOrigin], lastResponse: Option[DSAResponse]) {

  def addOrigin(origin: RequestOrigin) = copy(origins = this.origins + origin)

  def removeOrigin(origin: RequestOrigin) = copy(origins = this.origins - origin)

  def withResponse(response: DSAResponse) = copy(lastResponse = Some(response))
}