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
import scala.concurrent.ExecutionContext


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

    def storeIt(message: SubscriptionNotificationMessage) = store ! PutNotification(message)

    override def preStart(): Unit = {
      // a detached stage needs to start upstream demand
      // itself as it is not triggered by downstream demand
      pull(in)
    }

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

    })

    private def toResponseMsg(in:Seq[SubscriptionNotificationMessage]):Option[ResponseMessage] = {
      if (!in.isEmpty) {
        val msgId = in.head.msg
        Some(ResponseMessage(msgId, None, in.flatMap(_.responses).toList))
      } else None
    }

    def pushNext = {
      (store ? GetAndRemoveNext).mapTo[Option[(Int, mutable.Queue[SubscriptionNotificationMessage])]].foreach {
        _.foreach {
          case (sid, queue) => toResponseMsg(queue) foreach {m => push(out, m) }
        }
      }
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (isClosed(in)) {
          (store ? IsEmpty).mapTo[Boolean] foreach {
            isEmpty => if(isEmpty) completeStage()
          }
        } else if (!hasBeenPulled(in)) {
          pull(in)
        }
      }
    })
  }

}
