package models.actors

import scala.util.Try

import org.slf4j.LoggerFactory

import akka.actor.{ Actor, ActorRef, actorRef2Scala }
import models.{ MessageRouter, RequestEnvelope }
import models.rpc.DSARequest
import play.api.cache.CacheApi

/**
 * Akka-based implementation of the router. It uses the provided cache to locate the target actor.
 */
class AkkaRouter(cache: CacheApi) extends MessageRouter {

  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Retrieves the target ActorRef from the cache and sends all requests as a single envelope.
   */
  def routeRequests(source: String, target: String,
                    requests: DSARequest*)(implicit sender: ActorRef = Actor.noSender) = Try {
    val ref = cache.get[ActorRef](target).get
    val envelope = RequestEnvelope(source, target, requests)
    log.trace(s"Sending $envelope from [$source] to [$target] as actor $ref")
    ref ! envelope
  } recover {
    case e: NoSuchElementException => throw new IllegalArgumentException(s"Actor not found for path [$target]")
  }
}