package models

import scala.concurrent.duration.Duration

import org.apache.kafka.common.serialization._
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.kstream.{ KStream, KStreamBuilder }
import org.apache.kafka.streams.processor.{ ProcessorContext, TopologyBuilder }
import org.apache.kafka.streams.state.{ KeyValueStore, Stores }
import org.slf4j.Logger

import com.typesafe.config.Config

import cakesolutions.kafka.KafkaProducer
import models.kafka.CallRecord
import play.api.libs.json.{ Json, Reads, Writes }

/**
 * Types and utility functions for Kafka.
 */
package object kafka {

  private val utf8 = java.nio.charset.StandardCharsets.UTF_8

  /**
   * RequestEnvelope <-> Kafka
   */
  implicit val RequestEnvelopeSerializer = serializer[RequestEnvelope]
  implicit val RequestEnvelopeDeserializer = deserializer[RequestEnvelope]
  implicit val ReqEnvSerde = Serdes.serdeFrom(RequestEnvelopeSerializer, RequestEnvelopeDeserializer)

  /**
   * ResponseEnvelope <-> Kafka
   */
  implicit val ResponseEnvelopeSerializer = serializer[ResponseEnvelope]
  implicit val ResponseEnvelopeDeserializer = deserializer[ResponseEnvelope]
  implicit val RspEnvSerde = Serdes.serdeFrom(ResponseEnvelopeSerializer, ResponseEnvelopeDeserializer)

  /**
   * Origin <-> Kafka
   */
  implicit val OriginFormat = Json.format[Origin]
  implicit val OriginSerde = Serdes.serdeFrom(serializer[Origin], deserializer[Origin])

  /**
   * CallRecord <-> Kafka
   */
  implicit val CallRecordFormat = Json.format[CallRecord]
  implicit val CallRecordSerde = Serdes.serdeFrom(serializer[CallRecord], deserializer[CallRecord])

  /**
   * String <-> Kafka
   */
  implicit val StringSerializer = new StringSerializer
  implicit val StringDeserializer = new StringDeserializer
  implicit val StringSerde = Serdes.String

  /**
   * Integer <-> Kafka
   */
  implicit val IntegerSerde = Serdes.Integer

  /**
   * Creates a Kafka serializer for a class that already has JSON Writes defined.
   */
  def serializer[T: Writes]: Serializer[T] = new Serializer[T] {
    override def serialize(topic: String, data: T): Array[Byte] = if (data == null) null else {
      val js = Json.toJson(data)
      js.toString.getBytes(utf8)
    }

    override def configure(configs: java.util.Map[String, _], isKey: Boolean): Unit = ()
    override def close(): Unit = ()
  }

  /**
   * Creates a Kafka deserializer for a class that already has JSON Reads defined.
   */
  def deserializer[T: Reads]: Deserializer[T] = new Deserializer[T] {
    override def deserialize(topic: String, data: Array[Byte]): T = {
      val str = new String(data, utf8)
      Json.parse(str).as[T]
    }

    override def configure(configs: java.util.Map[String, _], isKey: Boolean): Unit = ()
    override def close(): Unit = ()
  }

  /**
   * Creates a new Kafka producer.
   */
  def createProducer[K: Serializer, V: Serializer](brokerUrl: String, config: Config) = {
    import org.apache.kafka.clients.producer.ProducerConfig._

    val kser = implicitly[Serializer[K]]
    val vser = implicitly[Serializer[V]]

    val conf = KafkaProducer.Conf(config, kser, vser).withProperty(BOOTSTRAP_SERVERS_CONFIG, brokerUrl)
    KafkaProducer(conf)
  }

  /**
   * Provides additional functionality to [[ProcessorContext]].
   */
  implicit class RichProcessorContext(val ctx: ProcessorContext) extends AnyVal {
    def getKeyValueStore[K, V](name: String) = ctx.getStateStore(name).asInstanceOf[KeyValueStore[K, V]]
    def schedule(duration: Duration) = ctx.schedule(duration.toMillis)
  }

  /**
   * Provides additional functionality to [[KStreamBuilder]].
   */
  implicit class RichStreamBuilder(val builder: KStreamBuilder) extends AnyVal {
    def newStream[K, V](topics: String*)(implicit ks: Serde[K], vs: Serde[V]): KStream[K, V] =
      builder.stream(ks, vs, topics: _*)
  }

  /**
   * Provides additional functionality to [[TopologyBuilder]].
   */
  implicit class RichTopologyBuilder(val builder: TopologyBuilder) extends AnyVal {
    def addKeyValueStore[K, V](name: String, persistent: Boolean)(implicit ks: Serde[K], vs: Serde[V]) = {
      val factory = Stores.create(name).withKeys(ks).withValues(vs)
      val store = if (persistent) factory.persistent.build else factory.inMemory.build
      builder.addStateStore(store)
    }
  }

  /**
   * Provides additional functionality to [[KStream]].
   */
  implicit class RichKStream[K, V](val stream: KStream[K, V])(implicit ks: Serde[K], vs: Serde[V]) {
    def materialize(topic: String): KStream[K, V] = stream.through(ks, vs, topic)
    def filterValues(predicate: V => Boolean) = stream.filter((_, v) => predicate(v))
    def filterNotValues(predicate: V => Boolean) = stream.filterNot((_, v) => predicate(v))
    def extractKey[K1](f: V => K1) = stream.selectKey((_, v) => f(v))
    def debug(log: Logger, name: String) = stream.foreach { (k, v) => log.debug(s"[$name]: $k -> $v") }
  }

  /**
   * Converts a (key, value) tupple into KeyValue(key, value).
   */
  implicit def tupleToKeyValue[K, V](tuple: (K, V)) = KeyValue.pair(tuple._1, tuple._2)
}