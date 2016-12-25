package org.dsa.iot.broker

import scala.collection.JavaConverters.asScalaSetConverter

import org.apache.kafka.streams.StreamsConfig

import com.typesafe.config.ConfigFactory

/**
 * DSA Broker settings.
 */
object Settings {
  private lazy val config = ConfigFactory.load

  object Topics {
    private val brokerTopics = config.getConfig("broker.topics")
    
    val RequestIn = brokerTopics.getString("request.in")
    val DownstreamIn = brokerTopics.getString("downstream.in")
    val InternalIn = brokerTopics.getString("internal.in")
    val RequestOut = brokerTopics.getString("request.out")
  }

  object Stores {
    private val brokerStores = config.getConfig("broker.stores")

    val RequestDestinations = brokerStores.getString("request.destinations")
    val RidGenerator = brokerStores.getString("rid.generator")
    val SidGenerator = brokerStores.getString("sid.generator")
    val SubscriptionByPath = brokerStores.getString("subscription.by.path")
    val SubscriptionKeyById = brokerStores.getString("subscriptionKey.by.id")
  }

  val KafkaStreamsConfig = {
    val cfg = config.getConfig("kafka-streams")
    val props = new java.util.Properties
    cfg.entrySet.asScala foreach { entry =>
      props.setProperty(entry.getKey, entry.getValue.unwrapped.toString)
    }
    new StreamsConfig(props)
  }
  
  val DownstreamPrefix = "/downstream"
}