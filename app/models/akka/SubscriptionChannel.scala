package models.akka

import akka.actor.{ActorRef, ActorSystem}
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler, StageLogging}
import models.rpc.{DSAMessage, ResponseMessage, SubscriptionNotificationMessage}
import akka.pattern.ask
import akka.util.Timeout
import models.akka.Messages._

import scala.concurrent.duration._
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}


class SubscriptionChannel(val store: ActorRef)
                         (implicit actorSystem: ActorSystem, materializer: Materializer)
  extends GraphStage[FlowShape[SubscriptionNotificationMessage, DSAMessage]] {

  type Sid = Int

  val in = Inlet[SubscriptionNotificationMessage]("Subscriptions.in")
  val out = Outlet[DSAMessage]("Subscriptions.out")
  implicit val as = actorSystem
  implicit val m = materializer
  implicit val ctx = ExecutionContext.global

  implicit val timeout = Timeout(3 seconds)

  override def shape: FlowShape[SubscriptionNotificationMessage, DSAMessage] = FlowShape.of(in, out)


  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with StageLogging {

    def storeIt(message: SubscriptionNotificationMessage) = Await.result(store ? PutNotification(message), 1 second)


    setHandler(in, new InHandler {
      override def onPush(): Unit = {

        log.debug(s"on push: ${in}")
        val message = grab(in)

        storeIt(message)
        log.debug(s"storing $message")

        if(isAvailable(out)){
          log.debug(s"out is available. Pushing $message")
          pushNext
          pull(in)
        } else {
          log.debug(s"out is unavailable.")
        }
      }

      override def onUpstreamFinish(): Unit = {
        val futureTail = (store ? GetAllMessages).mapTo[Map[Int, mutable.Queue[SubscriptionNotificationMessage]]]
        val tail = Await.result(futureTail, 1 second)
        if (tail.nonEmpty) {
          val leftItems = tail.mapValues(toResponseMsg(_)).values.filter(_.isDefined).map(_.get)
          emitMultiple(out, leftItems.iterator)
        }
        completeStage()
      }

    })

    private def toResponseMsg(in:Seq[SubscriptionNotificationMessage]):Option[ResponseMessage] = {
      if (!in.isEmpty) {
        val msgId = in.head.msg
        Some(ResponseMessage(msgId, None, in.flatMap(_.responses).toList))
      } else None
    }

    def pushNext = {
      val futureMessage = (store ? GetAndRemoveNext).mapTo[Option[mutable.Queue[SubscriptionNotificationMessage]]]

      Await.result(futureMessage, 1 second) foreach {
        queue => toResponseMsg(queue) foreach {m =>
          log.debug(s"pushing $m")
          push(out, m)
        }
      }
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        log.debug("on pull")
         if (!hasBeenPulled(in)) {
          pull(in)
        }
      }
    })
  }

}
