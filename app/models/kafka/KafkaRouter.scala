package models.kafka

import scala.util.Try

import org.apache.kafka.common.serialization.Serializer
import org.slf4j.LoggerFactory

import com.typesafe.config.Config

import akka.actor.{ Actor, ActorRef }
import cakesolutions.kafka.{ KafkaProducer, KafkaProducerRecord }
import models.{ MessageRouter, RequestEnvelope }
import models.rpc.{ DSARequest, DSAResponse }

/**
 * Kafka-based implementation of the router. It uses the target as the key when posting message
 * to the appropriate Kafka topic.
 */
class KafkaRouter(brokerUrl: String, config: Config, requestTopic: String) extends MessageRouter {

  private val log = LoggerFactory.getLogger(getClass)

  private val reqProducer = createProducer[String, RequestEnvelope](brokerUrl, config)

  /**
   * Posts the request as a single message using target as the message key.
   */
  def routeRequests(from: String, to: String,
                    requests: DSARequest*)(implicit sender: ActorRef = Actor.noSender) = Try {
    val envelope = RequestEnvelope(from, to, requests)
    log.trace(s"Posting $envelope from [$from] to [$to] via topic $requestTopic")
    val record = KafkaProducerRecord(requestTopic, to, envelope)
    reqProducer.send(record)
  }

  /**
   * Posts the request as a single message using target as the message key.
   */
  def routeResponses(from: String, to: String,
                     responses: DSAResponse*)(implicit sender: ActorRef = Actor.noSender) = Try {
    ???
  }

  /**
   * Creates a new Kafka producer.
   */
  private def createProducer[K: Serializer, V: Serializer](brokerUrl: String, config: Config) = {
    import org.apache.kafka.clients.producer.ProducerConfig._

    val kser = implicitly[Serializer[K]]
    val vser = implicitly[Serializer[V]]

    val conf = KafkaProducer.Conf(config, kser, vser).withProperty(BOOTSTRAP_SERVERS_CONFIG, brokerUrl)
    KafkaProducer(conf)
  }
}