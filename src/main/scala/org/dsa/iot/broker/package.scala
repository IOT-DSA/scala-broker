package org.dsa.iot

import scala.concurrent.duration.Duration
import scala.reflect.{ ClassTag, classTag }
import scala.util.matching.Regex

import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.kstream.{ KStream, KStreamBuilder }
import org.apache.kafka.streams.processor.{ ProcessorContext, TopologyBuilder }
import org.apache.kafka.streams.state.{ KeyValueStore, Stores }
import org.dsa.iot.broker.{ JsonExport, JsonFactory }
import org.json4s.native.JsonMethods.{ compact, parse, render }
import org.json4s.string2JsonInput

/**
 * DSA Broker helper functions and types.
 */
package object broker {

  /**
   * Interpolates strings to produce RegEx.
   */
  implicit class RegexContext(val sc: StringContext) extends AnyVal {
    def r = new Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
  }

  /**
   * Provides additional functionality to [[ProcessorContext]].
   */
  implicit class RichProcessorContext(val ctx: ProcessorContext) extends AnyVal {
    def getKeyValueStore[K, V](name: String) = ctx.getStateStore(name).asInstanceOf[KeyValueStore[K, V]]
    def schedule(duration: Duration) = ctx.schedule(duration.toMillis)
  }

  /**
   * Provides additional functionality to [[TopologyBuilder]].
   */
  implicit class RichTopologyBuilder(val builder: TopologyBuilder) extends AnyVal {
    def addKeyValueStore[K: ClassTag, V: ClassTag](name: String, persistent: Boolean) = {
      val factory = Stores.create(name).withKeys(classFor[K]).withValues(classFor[V])
      val store = if (persistent) factory.persistent.build else factory.inMemory.build
      builder.addStateStore(store)
    }
  }

  /**
   * Provides additional functionality to [[KStreamBuilder]].
   */
  implicit class RichStreamBuilder(val builder: KStreamBuilder) extends AnyVal {
    def newStream[K: ClassTag, V: ClassTag](topics: String*): KStream[K, V] =
      builder.stream(serdeFor[K], serdeFor[V], topics: _*)
  }

  /**
   * Provides additional functionality to [[KStream]].
   */
  implicit class RichKStream[K: ClassTag, V: ClassTag](val stream: KStream[K, V]) {
    def materialize(topic: String): KStream[K, V] = {
      val keyClass = classTag[K].runtimeClass.asInstanceOf[Class[K]]
      val valueClass = classTag[V].runtimeClass.asInstanceOf[Class[V]]
      stream.through(serdeFor[K], serdeFor[V], topic)
    }
  }

  /**
   * Renders a JSON representation of an object.
   */
  def exportAsJson(x: JsonExport) = (compact(render(x.toJson)))

  /**
   * Builds an object from JSON.
   */
  def importFromJson[T](factory: JsonFactory[T])(str: String) = factory.fromJson(parse(str))

  /**
   * Returns the runtime class for the specified class tag.
   */
  def classFor[T: ClassTag] = classTag[T].runtimeClass.asInstanceOf[Class[T]]

  /**
   * Returns the SerDe for the specified class tag.
   */
  def serdeFor[T: ClassTag] = Serdes.serdeFrom(classFor[T])
}