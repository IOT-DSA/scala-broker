package models.akka

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler, StageLogging}
import models.{ResponseEnvelope, SubscriptionResponseEnvelope}
import models.rpc.DSAResponse

import collection.mutable.HashMap
import scala.collection.mutable


class SubscriptionChannel(val maxCapacity: Int = 30)
                         (implicit actorSystem: ActorSystem, materializer: Materializer)
  extends GraphStage[FlowShape[SubscriptionResponseEnvelope, ResponseEnvelope]] {

  type Sid = Int

  val in = Inlet[SubscriptionResponseEnvelope]("Subscriptions.in")
  val out = Outlet[ResponseEnvelope]("Subscriptions.out")
  implicit val as = actorSystem
  implicit val m = materializer
  val store = new HashMap[Sid, mutable.Queue[SubscriptionResponseEnvelope]]

  override def shape: FlowShape[SubscriptionResponseEnvelope, ResponseEnvelope] = FlowShape.of(in, out)


  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with StageLogging {

    def queueSize(key: Sid) = store.get(key).map(_.size).getOrElse(0)

    // in case of data overflow
    def shouldDislodge(key: Sid) = queueSize(key) >= maxCapacity

    def storeIt(value: SubscriptionResponseEnvelope) = value match {
      case item@SubscriptionResponseEnvelope(_, _, QoS.Default) => {
        store.put(value.sid, mutable.Queue(value))
        log.debug("QoS == 0. Replacing with new queue")
      }
      case item@SubscriptionResponseEnvelope(_, _, _) => {
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
          // emit the rest if possible
          emitMultiple(out, store.map { item => ResponseEnvelope(item._2.map(_.response)) } toIterator)
        }
        completeStage()
      }
    })

    def pushNext = {
      //side effect
      val next = store.headOption

      next foreach { case (sid, queue) =>
        if (!queue.isEmpty) {
          push(out, ResponseEnvelope(queue.map(_.response)))
        }
        store.remove(sid)
      }
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
//        if (store.nonEmpty) pushNext
        if (isClosed(in)) {
          if (store.isEmpty) completeStage()
        } else if (!hasBeenPulled(in)) {
          pull(in)
        }

      }
    })
  }

}
