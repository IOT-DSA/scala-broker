package org.dsa.iot.broker

import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.concurrent.duration.DurationInt

import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.kstream.TransformerSupplier
import org.apache.kafka.streams.state.KeyValueStore

/**
 * Provides DAO methods to support the requester handler. 
 */
class RequestHandlerDAO(ridGenStore: KeyValueStore[String, Int],
                        sidGenStore: KeyValueStore[String, Int],
                        subByPath: KeyValueStore[String, String],
                        subKeyById: KeyValueStore[String, String]) {

  /**
   * Generates a new RID for the given key starting from 1.
   */
  def generateRid(key: String): Int = {
    val newRid = Option(ridGenStore.get(key)).getOrElse(1)
    ridGenStore.put(key, newRid + 1)
    newRid
  }

  /**
   * Generates a new SID for the given key starting from 1.
   */
  def generateSid(key: String): Int = {
    val newSid = Option(sidGenStore.get(key)).getOrElse(1)
    sidGenStore.put(key, newSid + 1)
    newSid
  }

  /**
   * Returns the subscription info for the given method and path.
   */
  def getSubscription(pathKey: String): Option[Subscription] = Option(subByPath.get(pathKey)) map importFromJson(Subscription)

  /**
   * Returns the subscription key (method:path) for the given origin.
   */
  def getSubscriptionKey(origin: Origin): Option[String] = Option(subKeyById.get(origin.toKeyString))

  /**
   * Saves/updates the subscription in the map, indexed by method:path key.
   */
  def saveSubscription(subscription: Subscription): Unit = subByPath.put(subscription.pathKey, exportAsJson(subscription))

  /**
   * Saves the subscription key for the given origin.
   */
  def saveSubscriptionKey(origin: Origin, pathKey: String): Unit = subKeyById.put(origin.toKeyString, pathKey)

  /**
   * Deletes the subscription.
   */
  def deleteSubscription(pathKey: String) = subByPath.delete(pathKey)

  /**
   * Deletes the subscription key.
   */
  def deleteSubscriptionKey(origin: Origin) = subKeyById.delete(origin.toKeyString)
}

/**
 * Handles requests to downstream responders.
 */
class DownstreamRequestHandler
    extends AbstractTransformer[String, RequestEnvelope, String, DSARequest](30 seconds) {
  import Settings._

  private lazy val ridGenStore = context.getKeyValueStore[String, Int](Stores.RidGenerator)
  private lazy val sidGenStore = context.getKeyValueStore[String, Int](Stores.SidGenerator)
  private lazy val subByPathStore = context.getKeyValueStore[String, String](Stores.SubscriptionByPath)
  private lazy val subKeyByIdStore = context.getKeyValueStore[String, String](Stores.SubscriptionKeyById)

  private lazy val dao = new RequestHandlerDAO(ridGenStore, sidGenStore, subByPathStore, subKeyByIdStore)

  def transform(to: String, envelope: RequestEnvelope) = envelope.request match {

    case ListRequest(rid, path) =>
      val oldSub = dao.getSubscription(ListRequest.MethodName + ":" + path)
      oldSub match {
        case Some(sub) =>
          val newSub = sub + envelope.origin
          dao.saveSubscription(newSub)
          dao.saveSubscriptionKey(envelope.origin, newSub.pathKey)
          null
        case None =>
          val newRid = dao.generateRid(to)
          val newSub = Subscription(newRid, ListRequest.MethodName, path, envelope.origin)
          dao.saveSubscription(newSub)
          dao.saveSubscriptionKey(envelope.origin, newSub.pathKey)
          new KeyValue(to, ListRequest(newRid, augment(path, to)))
      }

    case SetRequest(rid, path, value, permit) =>
      val newRid = dao.generateRid(to)
      val sub = Subscription(newRid, SetRequest.MethodName, path, envelope.origin)
      dao.saveSubscription(sub)
      dao.saveSubscriptionKey(envelope.origin, sub.pathKey)
      new KeyValue(to, SetRequest(newRid, augment(path, to), value, permit))

    case RemoveRequest(rid, path) =>
      val newRid = dao.generateRid(to)
      val sub = Subscription(newRid, SetRequest.MethodName, path, envelope.origin)
      dao.saveSubscription(sub)
      dao.saveSubscriptionKey(envelope.origin, sub.pathKey)
      new KeyValue(to, RemoveRequest(newRid, augment(path, to)))

    case InvokeRequest(rid, path, params, permit) =>
      val newRid = dao.generateRid(to)
      val sub = Subscription(newRid, SetRequest.MethodName, path, envelope.origin)
      dao.saveSubscription(sub)
      dao.saveSubscriptionKey(envelope.origin, sub.pathKey)
      new KeyValue(to, InvokeRequest(newRid, augment(path, to), params, permit))

    case SubscribeRequest(rid, SubscriptionPath(path, sid, qos) :: Nil) =>
      val oldSub = dao.getSubscription(SubscribeRequest.MethodName + ":" + path)
      oldSub match {
        case Some(sub) =>
          val newSub = sub + envelope.origin
          dao.saveSubscription(newSub)
          dao.saveSubscriptionKey(envelope.origin, newSub.pathKey)
          null
        case None =>
          val newRid = dao.generateRid(to)
          val newSid = dao.generateSid(to)
          val newSub = Subscription(newSid, SubscribeRequest.MethodName, path, envelope.origin)
          dao.saveSubscription(newSub)
          dao.saveSubscriptionKey(envelope.origin, newSub.pathKey)
          new KeyValue(to, SubscribeRequest(newRid, SubscriptionPath(augment(path, to), newSid, qos)))
      }

    case UnsubscribeRequest(rid, sid :: Nil) =>
      val subKey = dao.getSubscriptionKey(envelope.origin)
      dao.deleteSubscriptionKey(envelope.origin)
      subKey flatMap dao.getSubscription match {
        case Some(sub) =>
          val newSub = sub - envelope.origin
          if (newSub.isEmpty) {
            dao.deleteSubscription(newSub.pathKey)
            val newRid = dao.generateRid(to)
            new KeyValue(to, UnsubscribeRequest(newRid, sub.rid))
          } else {
            dao.saveSubscription(newSub)
            null
          }
        case None => log.error(s"No active subscription found for ${envelope.origin}"); null
      }

    case CloseRequest(_) =>
      val subKey = dao.getSubscriptionKey(envelope.origin)
      dao.deleteSubscriptionKey(envelope.origin)
      subKey flatMap dao.getSubscription match {
        case Some(sub) =>
          val newSub = sub - envelope.origin
          if (newSub.isEmpty) {
            dao.deleteSubscription(newSub.pathKey)
            new KeyValue(to, CloseRequest(sub.rid))
          } else {
            dao.saveSubscription(newSub)
            null
          }
        case None => log.error(s"No active subscription found for ${envelope.origin}"); null
      }

    case req @ _ => log.error(s"Invalid request: $req"); null
  }

  override def punctuate(ts: Long) = {
    log.debug("SubByPath: " + subByPathStore.all.asScala.mkString(","))
    log.debug("SubKeyById: " + subKeyByIdStore.all.asScala.mkString(","))
    null
  }

  private def augment(path: String, to: String) = path.drop(to.size)
}

/**
 * DownstreamRequestHandler factory.
 */
object DownstreamRequestHandler extends TransformerSupplier[String, RequestEnvelope, KeyValue[String, DSARequest]] {
  def get = new DownstreamRequestHandler
}