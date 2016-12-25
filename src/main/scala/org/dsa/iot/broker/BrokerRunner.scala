package org.dsa.iot.broker

import scala.collection.JavaConverters.asJavaIterableConverter

import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.kstream.KStreamBuilder
import org.json4s.jvalue2monadic
import org.json4s.native.JsonMethods.parse
import org.json4s.string2JsonInput

/**
 * Broker application, routes requests between origin (requester) and destination (responder).
 */
object BrokerRunner extends App {
  import Settings._

  val builder = new KStreamBuilder

  // 0. create stores
  builder.addKeyValueStore[String, String](Stores.RequestDestinations, false)
  builder.addKeyValueStore[String, Integer](Stores.RidGenerator, false)
  builder.addKeyValueStore[String, Integer](Stores.SidGenerator, false)
  builder.addKeyValueStore[String, String](Stores.SubscriptionByPath, false)
  builder.addKeyValueStore[String, String](Stores.SubscriptionKeyById, false)

  createRequestFlow

  val stream = new KafkaStreams(builder, KafkaStreamsConfig)
  sys.addShutdownHook(stream.close)
  stream.cleanUp
  stream.start

  private def createRequestFlow() = {
    // 1. pick (from, msg) from request topic
    // 2. flatMap msg into individual requests (with a nested flatMap of SUBSCRIBE/UNSUBSCRIBE requests)
    val input = builder.newStream[String, String](Topics.RequestIn)
    val requests = input flatMapValues { message =>
      val json = parse(message)
      val reqs = (json \ "requests").children map DSARequest.fromJson flatMap {
        case req: SubscribeRequest   => req.split
        case req: UnsubscribeRequest => req.split
        case req @ _                 => req :: Nil
      }
      reqs.toIterable.asJava
    }

    // 3. run each request through a processor/transformer to create (to, request):
    //   3.1. if request contains path, extract "to" from it (like "/downstream/ResponderA") and save to store
    //        under key "from:rid/sid"
    //   3.2. if request does not contain path ("close"), retrieve it from store for key "from:rid/sid"
    //   3.3. Build an envelope around request (from, to, request)
    //   3.3. Output (to, envelope)
    val envelopes = requests.transform(RequestRouter, Stores.RequestDestinations).mapValues(exportAsJson(_))

    // 4.split into downstream and internal streams and materialize them through different topics
    val downstream = envelopes filter { (key, _) =>
      key.startsWith(DownstreamPrefix)
    } materialize Topics.DownstreamIn mapValues { importFromJson(RequestEnvelope)(_) }
    val internal = envelopes filterNot { (key, _) =>
      key.startsWith(DownstreamPrefix)
    } materialize Topics.InternalIn mapValues { importFromJson(RequestEnvelope)(_) }

    // 5. process downstream requests
    //   5.1. if INVOKE/SET/REMOVE => generate rspRID. store (to, rspRID) -> (from, reqRID). forward (to, newRequest)
    //   5.2. if LIST => get a list of (from, reqRID) from subscription store for path. add itself. if was empty, do as above
    //   5.3. if SUBSCRIBE => same as LIST
    //   5.4. if CLOSE => retrieve rspID from RID store.
    val outRequests = downstream.transform(DownstreamRequestHandler, Stores.RidGenerator, Stores.SidGenerator,
      Stores.SubscriptionByPath, Stores.SubscriptionKeyById)
    outRequests.mapValues(exportAsJson(_)).materialize(Topics.RequestOut)
  }
}