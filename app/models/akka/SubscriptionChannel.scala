package models.akka

import akka.actor.{ActorRef, ActorSystem}
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler, StageLogging}
import models.rpc.{DSAMessage, ResponseMessage}
import akka.pattern.ask
import akka.util.Timeout
import models.Settings
import models.akka.Messages._
import models.akka.QoSState.{GetAllMessages, GetAndRemoveNext, PutNotification}

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}


class SubscriptionChannel(val store: ActorRef)
                         (implicit actorSystem: ActorSystem, materializer: Materializer)
  extends GraphStage[FlowShape[SubscriptionNotificationMessage, DSAMessage]] {

  type Sid = Int

  val in = Inlet[SubscriptionNotificationMessage]("Subscriptions.in")
  val out = Outlet[DSAMessage]("Subscriptions.out")
  implicit val ctx = actorSystem.dispatcher

  val timeout = Settings.QueryTimeout

  implicit val implTimeout = Timeout(timeout)

  override def shape: FlowShape[SubscriptionNotificationMessage, DSAMessage] = FlowShape.of(in, out)


  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with StageLogging {

    def storeIt(message: SubscriptionNotificationMessage) = (store ? PutNotification(message)).mapTo[Int]


    setHandler(in, new InHandler {
      override def onPush(): Unit = {

        log.debug("on push: {}", in)
        val message = grab(in)

        val callback = getAsyncCallback[Int]{
          _=>
            log.debug("storing {}", message)

            if(isAvailable(out)){
              pushNext
            } else {
              log.debug("out is unavailable.")
            }
        }

        storeIt(message) foreach callback.invoke

      }

      override def onUpstreamFinish(): Unit = {
        val futureTail = (store ? GetAllMessages).mapTo[Map[Int, Queue[SubscriptionNotificationMessage]]]
        val tail = Await.result(futureTail, timeout)
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
      // to avoid locking using 'getAsyncCallback' https://doc.akka.io/docs/akka/2.5/stream/stream-customize.html#using-asynchronous-side-channels
      val futureMessage = (store ? GetAndRemoveNext).mapTo[Option[Queue[SubscriptionNotificationMessage]]]

      val callback = getAsyncCallback[Option[Queue[SubscriptionNotificationMessage]]] {
        _ foreach {
          toResponseMsg(_).foreach { m =>
            log.debug("push(out, {})", m)
            push(out, m)
            pull(in)
          }
        }
      }

      futureMessage foreach {
        callback.invoke(_)
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
