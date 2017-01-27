package models

import scala.util.Try

import akka.actor.{ Actor, ActorRef }
import models.rpc.DSARequest

/**
 * An abstract router, responsible for delivering messages between requesters and responders.
 */
trait MessageRouter {

  /**
   * Routes the requests from source to target.
   */
  def routeRequests(source: String, target: String,
                    requests: DSARequest*)(implicit sender: ActorRef = Actor.noSender): Try[Unit]
}