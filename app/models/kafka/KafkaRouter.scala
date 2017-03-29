package models.kafka

import scala.util.Try

import org.apache.kafka.common.serialization.Serializer
import org.slf4j.LoggerFactory

import com.typesafe.config.Config

import akka.actor.ActorRef
import cakesolutions.kafka.{ KafkaProducer, KafkaProducerRecord }
import models.{ MessageRouter, RequestEnvelope, ResponseEnvelope }

/**
 * Kafka-based implementation of the router. It uses the target as the key when posting message
 * to the appropriate Kafka topic.
 */
class KafkaRouter(brokerUrl: String, config: Config, requestTopic: String, responseTopic: String)
    extends MessageRouter {

  private val log = LoggerFactory.getLogger(getClass)

  implicit private val reqProducer = {
    val clientId = "dsa_router_req" + hashCode
    createProducer[String, RequestEnvelope](brokerUrl, clientId, config)
  }
  implicit private val rspProducer = {
    val clientId = "dsa_router_rsp" + hashCode
    createProducer[String, ResponseEnvelope](brokerUrl, clientId, config)
  }

  /**
   * Posts the envelope to a Kafka topic using envelope destination as the message key.
   */
  def routeRequestEnvelope(envelope: RequestEnvelope) = route(requestTopic, envelope, envelope.to)

  /**
   * Posts the envelope to a Kafka topic using envelope origin as the message key.
   */
  def routeResponseEnvelope(envelope: ResponseEnvelope) = route(responseTopic, envelope, envelope.from)

  /**
   * Posts a message with the key to a Kafka topic.
   */
  private def route[T](topic: String, msg: T, key: String)(implicit producer: KafkaProducer[String, T]) = Try {
    log.debug(s"Posting $msg to topic $topic under key $key")
    val record = KafkaProducerRecord(topic, key, msg)
    producer.send(record)
  } map (_ => {})

  /**
   * Creates a new Kafka producer.
   */
  private def createProducer[K: Serializer, V: Serializer](brokerUrl: String, clientId: String,
                                                           config: Config): KafkaProducer[K, V] = {
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