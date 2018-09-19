package models.akka

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler, StageLogging}
import models.rpc.{DSAMessage, DSAResponse, ResponseMessage}
import akka.util.Timeout
import akka.event.LoggingAdapter
import models.Settings
import models.akka.Messages._
import models.metrics.Meter

import scala.collection.mutable.Queue
import scala.collection.mutable


class SubscriptionChannel(log: LoggingAdapter)
  extends GraphStage[FlowShape[Seq[SubscriptionNotificationMessage], DSAResponse]] with Meter {

  type Sid = Int
  val subscriptionsQueue = mutable.HashMap[Int, Queue[SubscriptionNotificationMessage]]()

  val in = Inlet[Seq[SubscriptionNotificationMessage]]("Subscriptions.in")
  val out = Outlet[DSAResponse]("Subscriptions.out")

  val timeout = Settings.QueryTimeout
  val maxBatch = Settings.Subscriptions.maxBatchSize
  val maxQosCapacity = Settings.Subscriptions.queueCapacity
  implicit val implTimeout = Timeout(timeout)

  override def shape: FlowShape[Seq[SubscriptionNotificationMessage], DSAResponse] = FlowShape.of(in, out)

  def putMessage(message: SubscriptionNotificationMessage) = {
    countTags("qos.notification.in.counter")
    histogramValue("qos.notification.in.hist")(1)
    val result = message match {
      case item@SubscriptionNotificationMessage(_, _, QoS.Default) =>
        if(subscriptionsQueue.contains(item.sid)){
          countTags(s"qos.level.0.dropped")
        }
        subscriptionsQueue += (item.sid -> Queue(message))
        log.debug("QoS == 0. Replacing with new queue")
        countTags(s"qos.level.0.total")
        item.sid
      case item@SubscriptionNotificationMessage(_, _, _) =>
        val maybeQ = subscriptionsQueue.get(item.sid).orElse(Some(Queue[SubscriptionNotificationMessage]()))

        maybeQ.foreach { q =>
          if(q.size > maxQosCapacity){
            q.dequeue()
            countTags(s"qos.level.${item.qos.index}.dropped")
          }
          q.enqueue(message)
          histogramValue(s"qos.level.${item.qos.index}.queue.size")(q.size)
        }

        subscriptionsQueue += (item.sid -> maybeQ.get)

        item.sid
      case other =>
    }

    histogramValue("qos.sids.size")(subscriptionsQueue.size)

    result
  }


  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with StageLogging {

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        log.debug("on push: {}", in)
        val messages = grab(in)
        messages.foreach(putMessage(_))
        if (isAvailable(out)) {
          pushNext
        }
      }

      override def onUpstreamFinish(): Unit = {

        val tail = subscriptionsQueue
        if (tail.nonEmpty) {
          val leftItems = tail.mapValues(_.map(toResponseMsg(_))).foreach { case (_, responses) =>
            emitMultiple(out, responses.toList)
          }
        }
        completeStage()
      }

    })

    private def toResponseMsg(in: SubscriptionNotificationMessage): DSAResponse = in.response


    def pushNext = {
      emitMultiple(out, subscriptionsQueue.valuesIterator.flatten.map(_.response))
      subscriptionsQueue.clear()
      if (!hasBeenPulled(in)) {
        pull(in)
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
