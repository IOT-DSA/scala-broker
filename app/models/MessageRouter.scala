package models

import scala.util.Try

import akka.actor.{ Actor, ActorRef }
import models.rpc.{ DSARequest, DSAResponse }

/**
 * An abstract router, responsible for delivering messages between requesters and responders.
 */
trait MessageRouter {

  /**
   * Indicates whether the responses are handled downstream, i.e. the sender should send
   * unprocessed responses to the router.
   */
  def delegateResponseHandling: Boolean

  /**
   * Routes the requests from source to destination.
   */
  def routeRequests(from: String, to: String, confirmed: Boolean,
                    requests: DSARequest*)(implicit sender: ActorRef = Actor.noSender): Try[Unit]

  /**
   * Routes pre-processed responses from source to destination.
   */
  def routeHandledResponses(from: String, to: String,
                            responses: DSAResponse*)(implicit sender: ActorRef = Actor.noSender): Try[Unit]

  /**
   * Routes unprocessed responses from source to destination.
   */
  def routeUnhandledResponses(from: String, responses: DSAResponse*)(implicit sender: ActorRef = Actor.noSender): Try[Unit]

  /**
   * Cleans up the resources.
   */
  def close(): Unit
}