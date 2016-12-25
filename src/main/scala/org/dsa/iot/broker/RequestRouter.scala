package org.dsa.iot.broker

import org.apache.kafka.streams.KeyValue

/**
 * For Subscribe it assumes exactly one path.
 * For Unsubscribe it assumes exactly one sid.
 */
class RequestRouter extends AbstractTransformer[String, DSARequest, String, RequestEnvelope] {
  import Settings._
  
  private lazy val store = context.getKeyValueStore[String, String](Stores.RequestDestinations)

  def transform(from: String, request: DSARequest) = {
    val envelope = RequestEnvelope(from, request)
    val key = envelope.origin.toKeyString
    val to = getRequestDestination(request) getOrElse store.get(key)
    store.putIfAbsent(key, to)
    new KeyValue(to, envelope)
  }

  private def getRequestDestination(req: DSARequest) = (req match {
    case ListRequest(_, path)         => Some(path)
    case SetRequest(_, path, _, _)    => Some(path)
    case RemoveRequest(_, path)       => Some(path)
    case InvokeRequest(_, path, _, _) => Some(path)
    case SubscribeRequest(_, paths)   => Some(paths.head.path)
    case UnsubscribeRequest(_, _)     => None
    case CloseRequest(_)              => None
  }) map RequestRouter.resolveDestination
}

/**
 * RequestRouter factory.
 */
object RequestRouter extends AbstractTransformerSupplier[String, DSARequest, String, RequestEnvelope] {
  def get = new RequestRouter
  
  def resolveDestination(path: String) = path match {
    case r"/data(/.*)?$_" => "/data"
    case r"/defs(/.*)?$_" => "/defs"
    case r"/sys(/.*)?$_" => "/sys"
    case r"/users(/.*)?$_" => "/users"
    case r"/downstream/(\w+)$responder(/.*)?$_" => s"/downstream/$responder"
    case r"/upstream/(\w+)$broker(/.*)?$_" => s"/upstream/$broker"
  }    
}