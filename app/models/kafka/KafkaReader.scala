package models.kafka

import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.JavaConverters.{ iterableAsScalaIterableConverter, seqAsJavaListConverter }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

import org.apache.kafka.common.serialization.{ Deserializer, Serializer }
import org.slf4j.LoggerFactory

import com.typesafe.config.Config

import akka.actor.{ ActorRef, actorRef2Scala }
import cakesolutions.kafka.{ KafkaConsumer, KafkaProducer, KafkaProducerRecord }
import javax.inject.Singleton
import models.{ RequestEnvelope, ResponseEnvelope, Settings }
import play.api.cache.CacheApi

/**
 * Attaches consumers to REQ_OUT and RSP_OUT topics, where the request/response processors
 * publish envelopes to be delivered to their Web Socket destination.
 */
class KafkaReader(cache: CacheApi, settings: Settings) {
  import settings.Kafka._

  private val log = LoggerFactory.getLogger(getClass)

  private val started = new AtomicBoolean(false)

  // consume confirmed requests and route them to Responder actor
  private val reqOutThread = createConsumerThread[RequestEnvelope](Topics.ReqEnvelopeOut, 2000)

  // consume responses and route them to Requester actor
  private val rspOutThread = createConsumerThread[ResponseEnvelope](Topics.RspEnvelopeOut, 2000)

  // when the requester actor cannot be found - route to the DLQ 
  private val undeliveredPoducer = createProducerForUndelivered
  private val undeliveredThread = createConsumerThread[ResponseEnvelope](Topics.Undelivered, 5000)

  /**
   * `true` if the consumer threads have been started.
   */
  def isStarted = started.get

  /**
   * Starts the subscription.
   */
  def start() = synchronized {
    if (!isStarted) {
      reqOutThread.start
      rspOutThread.start
      started.set(true)
      log.info(s"Kafka subscription started")
    } else
      log.warn(s"Kafka subscription has already been started")
  }

  /**
   * Stops the subscription.
   */
  def stop() = synchronized {
    if (isStarted) {
      started.set(false)
      log.info(s"Kafka subscription stopped")
      reqOutThread.join
      rspOutThread.join
    } else
      log.warn(s"Kafka subscription has not been started")
  }

  /**
   * Creates a thread for polling a Kafka topic and routing the results to an actor.
   */
  private def createConsumerThread[V: Deserializer](topic: String, interval: Long) = {
    val consumer = createConsumer[String, V](BrokerUrl, Consumer)
    consumer.subscribe(List(topic).asJava)
    log.info(s"Kafka consumer created for topic $topic")

    val runnable = new Runnable {
      def run() = {
        log.debug(s"Polling started from topic $topic")
        while (isStarted) {
          val records = consumer.poll(interval).asScala
          if (!records.isEmpty)
            log.debug(s"${records.size} records received from topic $topic")
          records foreach { record =>
            val target = record.key
            val envelope = record.value
            send(envelope, target)
          }
        }
        consumer.close
      }
    }

    new Thread(runnable)
  }

  /**
   * Creates a new Kafka consumer.
   */
  private def createConsumer[K: Deserializer, V: Deserializer](brokerUrl: String, config: Config) = {
    import org.apache.kafka.clients.consumer.ConsumerConfig._

    val kdes = implicitly[Deserializer[K]]
    val vdes = implicitly[Deserializer[V]]

    val conf = KafkaConsumer.Conf(config, kdes, vdes)
      .withProperty(BOOTSTRAP_SERVERS_CONFIG, brokerUrl)
    KafkaConsumer(conf)
  }

  /**
   * Resolves the `to` actor and sends the message to its mailbox.
   */
  private def send(msg: Any, to: String): Try[Unit] = Try {
    val ref = cache.get[ActorRef](to).get
    log.trace(s"Sending $msg to [$to] as actor $ref")
    ref ! msg
  } recover {
    case e: NoSuchElementException => msg match {
      case rsp: ResponseEnvelope =>
        val record = KafkaProducerRecord(Topics.Undelivered, to, rsp)
        undeliveredPoducer.send(record) map (_ => {})
      case _ => throw new IllegalArgumentException(s"Actor not found for path [$to]")
    }
  }

  /**
   * Creates a new Kafka producer for posting undelivered messages.
   */
  private def createProducerForUndelivered: KafkaProducer[String, ResponseEnvelope] = {
    import org.apache.kafka.clients.producer.ProducerConfig._

    val kser = implicitly[Serializer[String]]
    val vser = implicitly[Serializer[ResponseEnvelope]]

    val clientId = "dsa_undelivered" + hashCode
    val conf = KafkaProducer.Conf(Producer, kser, vser)
      .withProperty(BOOTSTRAP_SERVERS_CONFIG, BrokerUrl)
      .withProperty(CLIENT_ID_CONFIG, clientId)
    KafkaProducer(conf)
  }
}