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
  extends GraphStage[FlowShape[SubscriptionNotificationMessage, DSAResponse]] with Meter {

  type Sid = Int
  val subscriptionsQueue = mutable.HashMap[Int, Queue[SubscriptionNotificationMessage]]()

  val in = Inlet[SubscriptionNotificationMessage]("Subscriptions.in")
  val out = Outlet[DSAResponse]("Subscriptions.out")

  val timeout = Settings.QueryTimeout
  var iter = subscriptionsQueue.iterator
  val maxBatch = Settings.Subscriptions.maxBatchSize
  val maxQosCapacity = Settings.Subscriptions.queueCapacity
  implicit val implTimeout = Timeout(timeout)

  override def shape: FlowShape[SubscriptionNotificationMessage, DSAResponse] = FlowShape.of(in, out)

  def putMessage(message: SubscriptionNotificationMessage) = {
    meterTags("qos.in.level." + message.qos.index.toString)
    val result = message match {
      case item@SubscriptionNotificationMessage(_, _, QoS.Default) =>
        subscriptionsQueue += (item.sid -> Queue(message))
        log.debug("QoS == 0. Replacing with new queue")
        histogramValue(s"qos.level.0.queue.size")(1)
        item.sid
      case item@SubscriptionNotificationMessage(_, _, _) =>
        val maybeQ = subscriptionsQueue.get(item.sid).orElse(Some(Queue[SubscriptionNotificationMessage]()))

        maybeQ.foreach { q =>
          if(q.size > maxQosCapacity){
            q.dequeue()
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

  def next: Option[(Int, Queue[SubscriptionNotificationMessage])] = {
    if (iter.hasNext) {
      Some(iter.next())
    } else {
      iter = subscriptionsQueue.iterator
      if (iter.hasNext) Some(iter.next())
      else None

    }
  }


  def getAndRemoveNext(): Option[SubscriptionNotificationMessage] = next.flatMap { case (key, queue) =>
    val item = if (queue.isEmpty) None
    else Some(queue.dequeue())

    if (queue.isEmpty) subscriptionsQueue.remove(key)
    item
  }


  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with StageLogging {

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        log.debug("on push: {}", in)
        val message = grab(in)
        putMessage(message)
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

      getAndRemoveNext().foreach { message =>
        val resp = message.response
        push(out, resp)
      }
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
