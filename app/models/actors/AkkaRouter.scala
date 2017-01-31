package models.actors

import scala.util.{ Success, Try }

import org.slf4j.LoggerFactory

import akka.actor.{ Actor, ActorRef, actorRef2Scala }
import models.{ MessageRouter, RequestEnvelope, ResponseEnvelope }
import models.rpc.{ DSARequest, DSAResponse }
import play.api.cache.CacheApi

/**
 * Akka-based implementation of the router. It uses the provided cache to locate the target actor.
 */
class AkkaRouter(cache: CacheApi) extends MessageRouter {
  private val nothingToDo = Success({})

  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Retrieves the target ActorRef from the cache and sends all requests as a single envelope.
   */
  def routeRequests(from: String, to: String,
                    requests: DSARequest*)(implicit sender: ActorRef = Actor.noSender) = {
    if (!requests.isEmpty)
      route(RequestEnvelope(from, to, requests), from, to)
    else
      nothingToDo
  }

  /**
   * Retrieves the target ActorRef from the cache and sends all responses as a single envelope.
   */
  def routeResponses(from: String, to: String,
                     responses: DSAResponse*)(implicit sender: ActorRef = Actor.noSender) = {
    if (!responses.isEmpty)
      route(ResponseEnvelope(from, to, responses), from, to)
    else
      nothingToDo
  }

  /**
   * Resolves the `to` actor and sends the message to its mailbox.
   */
  private def route(msg: Any, from: String, to: String)(implicit sender: ActorRef = Actor.noSender) = Try {
    val ref = cache.get[ActorRef](to).get
    log.trace(s"Sending $msg from [$from] to [$to] as actor $ref")
    ref ! msg
  } recover {
    case e: NoSuchElementException => throw new IllegalArgumentException(s"Actor not found for path [$to]")
  }
}