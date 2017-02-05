package models.kafka

import scala.util.{ Failure, Try }

import org.apache.kafka.common.serialization.Serializer
import org.slf4j.LoggerFactory

import com.typesafe.config.Config

import akka.actor.{ Actor, ActorRef }
import cakesolutions.kafka.{ KafkaProducer, KafkaProducerRecord }
import models.{ MessageRouter, RequestEnvelope, ResponseEnvelope }
import models.rpc.{ DSARequest, DSAResponse }

/**
 * Kafka-based implementation of the router. It uses the target as the key when posting message
 * to the appropriate Kafka topic.
 */
class KafkaRouter(brokerUrl: String, config: Config, requestTopic: String, responseTopic: String)
    extends MessageRouter {

  private val log = LoggerFactory.getLogger(getClass)

  private val reqProducer = {
    val clientId = "dsa_router_req" + hashCode
    createProducer[String, RequestEnvelope](brokerUrl, clientId, config)
  }
  private val rspProducer = {
    val clientId = "dsa_router_rsp" + hashCode
    createProducer[String, ResponseEnvelope](brokerUrl, clientId, config)
  }

  /**
   * Reponses are handled by the downstream Kafka process.
   */
  val delegateResponseHandling = true

  /**
   * Posts the requests as a single message using target as the message key.
   */
  def routeRequests(from: String, to: String, confirmed: Boolean,
                    requests: DSARequest*)(implicit sender: ActorRef = Actor.noSender) = Try {
    val envelope = RequestEnvelope(from, to, confirmed, requests)
    log.trace(s"Posting $envelope to topic $requestTopic")
    val record = KafkaProducerRecord(requestTopic, to, envelope)
    reqProducer.send(record)
  }

  /**
   * Raises an error, since Kafka router should only send unhandled response.
   */
  def routeHandledResponses(from: String, to: String,
                            responses: DSAResponse*)(implicit sender: ActorRef = Actor.noSender) =
    Failure(new UnsupportedOperationException("KafkaRouter should only route handled responses"))

  /**
   * Posts the responses as a single message using target as the message key.
   */
  def routeUnhandledResponses(from: String, responses: DSAResponse*)(implicit sender: ActorRef = Actor.noSender) = Try {
    val to = "???"
    val envelope = ResponseEnvelope(from, to, responses)
    log.trace(s"Posting $envelope to topic $responseTopic")
    val record = KafkaProducerRecord(responseTopic, from, envelope)
    rspProducer.send(record)
  }

  /**
   * Creates a new Kafka producer.
   */
  private def createProducer[K: Serializer, V: Serializer](brokerUrl: String, clientId: String, config: Config) = {
    import org.apache.kafka.clients.producer.ProducerConfig._

    val kser = implicitly[Serializer[K]]
    val vser = implicitly[Serializer[V]]

    val conf = KafkaProducer.Conf(config, kser, vser)
      .withProperty(BOOTSTRAP_SERVERS_CONFIG, brokerUrl)
      .withProperty(CLIENT_ID_CONFIG, clientId)
    KafkaProducer(conf)
  }

  /**
   * Closes the Kafka producer.
   */
  def close() = {
    reqProducer.close
    rspProducer.close
  }
}