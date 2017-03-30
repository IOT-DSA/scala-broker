package models.actors

import scala.util.Try

import org.slf4j.LoggerFactory

import akka.actor.{ ActorRef, actorRef2Scala }
import models.{ MessageRouter, RequestEnvelope, ResponseEnvelope }
import play.api.cache.CacheApi

/**
 * Akka-based implementation of the router. It delivers requests between the Requester and RRProcessor
 * and responses between Responder and RRProcessor, using the provided cache to locate the processor.
 */
class AkkaRouter(cache: CacheApi) extends MessageRouter {

  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Sends the envelope to the `processor` actor for the envelope's destination.
   * The lookup name of the processor actor is obtained by concatenating the destination
   * with [[RRProcessorActor.Suffix]].
   */
  def routeRequestEnvelope(envelope: RequestEnvelope) =
    route(envelope, envelope.from, envelope.to + RRProcessorActor.Suffix)

  /**
   * Sends the envelope to the `processor` actor for the envelope's origin.
   * The lookup name of the processor actor is obtained by concatenating the origin
   * with [[RRProcessorActor.Suffix]].
   */
  def routeResponseEnvelope(envelope: ResponseEnvelope) =
    route(envelope, envelope.from, envelope.from + RRProcessorActor.Suffix)

  /**
   * Does nothing.
   */
  def close() = {}

  /**
   * Routes a message resolving the target actor by the cache lookup.
   */
  private def route(msg: Any, from: String, to: String) = Try {
    val ref = cache.get[ActorRef](to).get
    log.debug(s"Sending $msg from [$from] to [$to]")
    ref ! msg
  } recover {
    case e: NoSuchElementException => throw new IllegalArgumentException(s"Actor not found for [$to]", e)
  }
}