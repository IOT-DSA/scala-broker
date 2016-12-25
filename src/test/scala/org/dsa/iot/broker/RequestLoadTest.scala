package org.dsa.iot.broker

import java.util.concurrent.atomic.AtomicLong

import scala.util.Random

import org.apache.kafka.common.serialization.StringSerializer
import org.json4s.JsonDSL.WithBigDecimal.{ int2jvalue, pair2Assoc, seq2jvalue }
import org.json4s.native.JsonMethods.{ compact, render }

import cakesolutions.kafka.{ KafkaProducer, KafkaProducerRecord }

/**
 * Launches multiple threads to post requests to Kafka (to be picked up and processed by the broker).
 */
object RequestLoadTest extends App {
  val topic = Settings.Topics.RequestIn
  val ser = new StringSerializer

  val msgCount = new AtomicLong(0)

  val RequesterCount = 1000
  val ResponderCount = 500

  class Requester(id: String) extends Runnable {
    private val conf = KafkaProducer.Conf(ser, ser)
    private val producer = KafkaProducer(conf)

    def run(): Unit = try {
      Stream.from(1) foreach { rid =>
        val target = "rsp" + Random.nextInt(ResponderCount)
        val path = s"/downstream/$target/node$rid"

        val req = ListRequest(rid, path)
        val envelope = ("msg" -> rid) ~ ("requests" -> List(req.toJson))
        val message = compact(render(envelope))

        val record = KafkaProducerRecord[String, String](topic, id, message)
        producer.send(record)
        println(s"$id: sent $message")
        msgCount.incrementAndGet

        Thread.sleep(Random.nextInt(10))
      }
    } finally {
      producer.close
    }
  }

  val threads = (1 to RequesterCount) map { idx =>
    val id = "rqs" + idx
    new Thread(new Requester(id))
  }

  val start = System.currentTimeMillis
  threads foreach (_.start)
  Thread.sleep(30000)
  threads foreach (_.interrupt)
  threads foreach (_.join)
  val end = System.currentTimeMillis

  val secondsPassed = (end - start) / 1000

  println(s"${msgCount.get} messages sent in $secondsPassed sec. Average RPS=${msgCount.get / secondsPassed}")
}