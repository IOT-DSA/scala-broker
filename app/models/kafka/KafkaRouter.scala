package models.kafka

import scala.util.Try

import org.apache.kafka.common.serialization.Serializer
import org.slf4j.LoggerFactory

import com.typesafe.config.Config

import akka.actor.{ Actor, ActorRef }
import cakesolutions.kafka.{ KafkaProducer, KafkaProducerRecord }
import models.{ MessageRouter, RequestEnvelope }
import models.rpc.DSARequest

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
  def routeRequests(source: String, target: String,
                    requests: DSARequest*)(implicit sender: ActorRef = Actor.noSender) = Try {
    val envelope = RequestEnvelope(source, target, requests)
    log.trace(s"Posting $envelope from [$source] to [$target] via topic $requestTopic")
    val record = KafkaProducerRecord(requestTopic, target, envelope)
    reqProducer.send(record)
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