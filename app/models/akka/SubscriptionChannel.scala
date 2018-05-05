package models.akka

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler, StageLogging}
import models.ResponseEnvelope
import models.rpc.{DSAMessage, DSAResponse, ResponseMessage, SubscriptionNotificationMessage}

import collection.mutable.HashMap
import scala.collection.mutable


class SubscriptionChannel(val maxCapacity: Int = 30)
                         (implicit actorSystem: ActorSystem, materializer: Materializer)
  extends GraphStage[FlowShape[SubscriptionNotificationMessage, DSAMessage]] {

  type Sid = Int

  val in = Inlet[SubscriptionNotificationMessage]("Subscriptions.in")
  val out = Outlet[DSAMessage]("Subscriptions.out")
  implicit val as = actorSystem
  implicit val m = materializer
  val store = new HashMap[Sid, mutable.Queue[SubscriptionNotificationMessage]]

  override def shape: FlowShape[SubscriptionNotificationMessage, DSAMessage] = FlowShape.of(in, out)


  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with StageLogging {

    def queueSize(key: Sid) = store.get(key).map(_.size).getOrElse(0)

    // in case of data overflow
    def shouldDislodge(key: Sid) = queueSize(key) >= maxCapacity

    def storeIt(value: SubscriptionNotificationMessage) = value match {
      case item @ SubscriptionNotificationMessage(_, _, _, _, QoS.Default) => {
        store.put(value.sid, mutable.Queue(value))
        log.debug("QoS == 0. Replacing with new queue")
      }
      case item @ SubscriptionNotificationMessage(_, _, _, _, _) => {
        val queue = store.get(item.sid) map { q =>
          if (shouldDislodge(item.sid)) q.dequeue()
          q += value
        } getOrElse (mutable.Queue(value))

        store.put(item.sid, queue)
        log.debug("QoS > 0. Adding new value to queue")
      }
    }

    override def preStart(): Unit = {
      // a detached stage needs to start upstream demand
      // itself as it is not triggered by downstream demand
      pull(in)
    }

    setHandler(in, new InHandler {
      override def onPush(): Unit = {

        log.debug(s"on push: ${in}")
        val elem = grab(in)

        storeIt(elem)
        log.debug(s"storing $elem")

        if(isAvailable(out)){
          log.debug(s"out is available. Pushing $elem")
          pushNext
          pull(in)
        } else {
          log.debug(s"out is unavailable.")
        }

      }

      override def onUpstreamFinish(): Unit = {
        if (store.nonEmpty) {
          val leftItems = store.mapValues(toResponseMsg(_)).values.filter(_.isDefined).map(_.get)
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
      //side effect
      val next = store.headOption

      next foreach { case (sid, queue) =>
        toResponseMsg(queue) foreach { push(out, _) }
        store.remove(sid)
      }
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (isClosed(in)) {
          if (store.isEmpty) completeStage()
        } else if (!hasBeenPulled(in)) {
          pull(in)
        }

      }
    })
  }

}
