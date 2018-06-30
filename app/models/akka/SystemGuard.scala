package models.akka

import javax.inject.Inject

import akka.actor.{ActorSystem, DeadLetter}
import com.google.inject.Singleton

@Singleton
class SystemGuard @Inject() (system:ActorSystem) {

  val deadLetterGuard = system.actorOf(DeadLettersGuard.props, "deadLetterGuard")

  system.eventStream.subscribe(
    deadLetterGuard, classOf[DeadLetter]
  )

}
