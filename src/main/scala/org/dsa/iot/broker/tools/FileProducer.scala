package org.dsa.iot.broker.tools

import scala.io.Source

import org.apache.kafka.common.serialization.StringSerializer

import cakesolutions.kafka.{ KafkaProducer, KafkaProducerRecord }

/**
 * Publishes a text file as a message on a Kafka topic.
 */
object FileProducer extends App {

  if (args.size < 2) {
    println("Usage: FileProducer topic filename [key]")
    sys.exit
  }

  val topic = args(0)
  val filename = args(1)
  val key = if (args.size > 2) Some(args(2)) else None

  val message = Source.fromFile(filename).mkString

  val ser = new StringSerializer

  val conf = KafkaProducer.Conf(ser, ser)
  val producer = KafkaProducer(conf)
  val record = KafkaProducerRecord[String, String](topic, key, message)
  producer.send(record)
  producer.flush
  producer.close

  println("Message sent to topic " + topic)
}