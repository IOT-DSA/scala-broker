package models.akka

import akka.actor.{ActorSystem, PoisonPill}
import akka.dispatch.{BoundedPriorityMailbox, PriorityGenerator}
import com.typesafe.config.Config
import models.akka.QoSState.GetAndRemoveNext
import scala.concurrent.duration._

class QoSPriorityMailBox (settings: ActorSystem.Settings, config: Config)
  extends BoundedPriorityMailbox(
    // Create a new PriorityGenerator, lower prio means more important
    PriorityGenerator {
      // 'highpriority messages should be treated first if possible
      case m:GetAndRemoveNext ⇒ 0

      // PoisonPill when no other left
      case PoisonPill    ⇒ 3

      // We default to 1, which is in between high and low
      case otherwise     ⇒ 2
    }, config.getInt("mailbox-capacity"), config.getDuration("mailbox-push-timeout-time").toMillis milliseconds)
