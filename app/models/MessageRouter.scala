package models

import scala.util.{ Success, Try }

import akka.actor.ActorRef
import models.rpc.{ DSARequest, DSAResponse }

/**
 * An abstract router, responsible for delivering messages between requesters and responders.
 */
trait MessageRouter {

  protected val nothingToDo = Success({})

  /**
   * Routes the requests from origin to destination. The router implementation may use various
   * mechanisms for delivering the messages: akka routing, pushing to a Kafka topic, etc.
   */
  def routeRequestEnvelope(envelope: RequestEnvelope)(implicit sender: ActorRef): Try[Unit]

  /**
   * Routes the responses from origin to destination. The router implementation may use various
   * mechanisms for delivering the messages: akka routing, pushing to a Kafka topic, etc.
   */
  def routeResponseEnvelope(envelope: ResponseEnvelope)(implicit sender: ActorRef): Try[Unit]

  /**
   * Creates a request envelope and delegates to `routeRequestEnvelope(envelope)` if the request
   * list is not empty.
   */
  def routeRequests(from: String, to: String,
                    requests: DSARequest*)(implicit sender: ActorRef): Try[Unit] = {
    if (!requests.isEmpty)
      routeRequestEnvelope(RequestEnvelope(from, to, false, requests))
    else
      nothingToDo
  }

  /**
   * Creates a response envelope and delegates to `routeResponseEnvelope(envelope)` if the response
   * list is not empty.
   */
  def routeResponses(from: String, to: String,
                     responses: DSAResponse*)(implicit sender: ActorRef): Try[Unit] = {
    if (!responses.isEmpty)
      routeResponseEnvelope(ResponseEnvelope(from, to, responses))
    else
      nothingToDo
  }

  /**
   * Cleans up the resources.
   */
  def close(): Unit
}