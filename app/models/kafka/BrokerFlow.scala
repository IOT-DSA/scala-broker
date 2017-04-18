package models.kafka

import scala.collection.JavaConverters.asJavaIterableConverter

import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.kstream.KStreamBuilder
import org.slf4j.LoggerFactory

import com.typesafe.config.ConfigFactory

import models.{ RequestEnvelope, ResponseEnvelope, Settings }
import play.api.Configuration
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * The request/response processor. Handles requests coming from Requester actors and responses
 * coming from Responder actors.
 */
object BrokerFlow {
  val settings = new Settings(new Configuration(ConfigFactory.load))
  import settings._

  val RidManager = CallRegistry("RID")
  val SidManager = CallRegistry("SID")

  private val log = LoggerFactory.getLogger(getClass)

  private var _stream: Option[KafkaStreams] = None

  /**
   * Application entry point.
   */
  def main(args: Array[String]): Unit = {
    start

    println("\nPress ENTER to stop")
    System.in.read

    stop
  }

  /**
   * Returns `true` if the streaming has started, `false` otherwise.
   */
  def isStarted = synchronized { _stream.isDefined }

  /**
   * Creates the flows and starts streaming.
   */
  def start() = synchronized {
    _stream match {
      case None =>
        val stream = createStreams; stream.cleanUp; _stream = Some(stream)
        Future { stream.start }
        log.info("Broker flow started")
      case Some(_) => log.warn("Broker flow has already been started")
    }
  }

  /**
   * Stops streaming.
   */
  def stop() = synchronized {
    _stream match {
      case Some(stream) =>
        stream.close; _stream = None; log.info("Broker flow stopped")
      case None => log.warn("Broker flow has not been started")
    }
  }

  /**
   * Creates streams.
   */
  private def createStreams() = {
    val builder = new KStreamBuilder

    createStores(builder)
    createRequestFlow(builder)
    createResponseFlow(builder)

    val stream = new KafkaStreams(builder, Kafka.Streams)
    stream.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler {
      def uncaughtException(thread: Thread, e: Throwable) = {
        log.error("Exception thrown, Kafka Streams terminated", e)
      }
    })
    sys.addShutdownHook(stream.close)

    stream
  }

  /**
   * creates stores
   */
  private def createStores(builder: KStreamBuilder) = {
    RidManager.createStores(builder)
    SidManager.createStores(builder)
    AttributeStore.createStores(builder)
  }

  /**
   * Creates request flow.
   */
  private def createRequestFlow(builder: KStreamBuilder) = {
    val input = builder.newStream[String, RequestEnvelope](Kafka.Topics.ReqEnvelopeIn)
    input.debug(log, "ReqEnvelopeIn")

    val results = input.transform(RequestHandler, RequestHandler.StoresNames: _*)

    val reqsToWs = results.mapValues(_._1).filterNotValues(_.requests.isEmpty).extractKey(_.to)
    reqsToWs.debug(log, "ReqEnvelopeOut")
    reqsToWs.to(StringSerde, ReqEnvSerde, Kafka.Topics.ReqEnvelopeOut)

    val rspsBack = results.mapValues(_._2).filterNotValues(_.responses.isEmpty).extractKey(_.to)
    rspsBack.debug(log, "RspEnvelopeOut")
    rspsBack.to(StringSerde, RspEnvSerde, Kafka.Topics.RspEnvelopeOut)
  }

  /**
   * Creates response flow.
   */
  private def createResponseFlow(builder: KStreamBuilder) = {
    val input = builder.newStream[String, ResponseEnvelope](Kafka.Topics.RspEnvelopeIn)
    input.debug(log, "RspEnvelopeIn")

    val results = input.transform(ResponseHandler, ResponseHandler.StoresNames: _*)

    val rsps = results.flatMapValues(_.toIterable.asJava).filterNotValues(_.responses.isEmpty).extractKey(_.to)
    rsps.debug(log, "RspEnvelopeOut")
    rsps.to(StringSerde, RspEnvSerde, Kafka.Topics.RspEnvelopeOut)
  }
}