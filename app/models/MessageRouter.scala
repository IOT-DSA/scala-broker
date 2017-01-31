package models

import scala.util.Try

import akka.actor.{ Actor, ActorRef }
import models.rpc.{ DSARequest, DSAResponse }

/**
 * An abstract router, responsible for delivering messages between requesters and responders.
 */
trait MessageRouter {

  /**
   * Routes the requests from source to destination.
   */
  def routeRequests(from: String, to: String,
                    requests: DSARequest*)(implicit sender: ActorRef = Actor.noSender): Try[Unit]

  /**
   * Routes the responses from source to destination.
   */
  def routeResponses(from: String, to: String,
                     responses: DSAResponse*)(implicit sender: ActorRef = Actor.noSender): Try[Unit]
}