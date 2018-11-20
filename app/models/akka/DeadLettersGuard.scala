package models.akka

import akka.actor.{Actor, ActorLogging, DeadLetter, Props}
import models.rpc.{DSAError, DSAResponse, StreamState}
import models.{RequestEnvelope, ResponseEnvelope}

class DeadLettersGuard extends Actor with ActorLogging {

  context.system.eventStream.subscribe(
    self, classOf[DeadLetter]
  )

  override def receive: Receive = {
    case DeadLetter(msg, toAnswer, requested) => msg match {
      case RequestEnvelope(requests, _) => {
        val responses = requests.map {
          r =>
            DSAResponse(r.rid, Some(StreamState.Closed), None, None, Some(DSAError(
              msg = Some(s"Unable to handle request"),
              detail = Some(
                s"""Couldn't deliver request: ${r.method} to actor ${requested.path}
                               possible reasons:
                                - path doesn't exist
                                - dslink owns this path been terminated or disconnected
                                - actor system unable to address this request
                """)
            )))
        }

        toAnswer ! ResponseEnvelope(responses)
      }
      case anyOther => log.error(
        s"""
          |Couldn't deliver message: ${msg},  to ${requested.path}, toAnswer: ${toAnswer.path}
        """.stripMargin)
    }
    case _ =>

  }
}

object DeadLettersGuard {
  def props = Props(new DeadLettersGuard())
}
