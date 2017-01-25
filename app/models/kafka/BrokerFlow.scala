package models.kafka

import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.kstream.KStreamBuilder
import org.slf4j.LoggerFactory

import com.typesafe.config.ConfigFactory

import models.Settings
import models.actors.RequestEnvelope
import play.api.Configuration
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.processor.ProcessorContext

object BrokerFlow extends App {
  val settings = new Settings(new Configuration(ConfigFactory.load))
  import settings._

  private val log = LoggerFactory.getLogger(getClass)

  private val builder = new KStreamBuilder

  createStores(builder)
  createRequestFlow(builder)

  val stream = new KafkaStreams(builder, Kafka.Streams)
  stream.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler {
    def uncaughtException(thread: Thread, e: Throwable) = {
      log.error("Exception thrown, Kafka Streams terminated", e)
    }
  })
  sys.addShutdownHook(stream.close)
  stream.cleanUp
  stream.start

  /**
   * creates stores
   */
  private def createStores(builder: KStreamBuilder) = {
    builder.addKeyValueStore[String, Integer]("RidGenerator", false)
    builder.addKeyValueStore[String, Integer]("SidGenerator", false)
  }

  /**
   * creates request flow
   */
  private def createRequestFlow(builder: KStreamBuilder) = {
    val input = builder.newStream[String, KafkaRequestEnvelope](Kafka.Topics.ReqEnvelopeIn)
    val output = input.transform(RequestHandler, "RidGenerator", "SidGenerator")
    output.materialize(Kafka.Topics.ReqEnvelopeOut)
  }
}