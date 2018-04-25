package models.akka

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import models.ResponseEnvelope
import models.rpc.DSAResponse

import collection.mutable.{HashMap, MultiMap}
import scala.collection.mutable

case class ResponseSidAndQoS(response:DSAResponse, sid:Int, qos:QoS.Level)


class SubscriptionChannel(
                           val safeCapacity:Int = 5,
                           val maxCapacity:Int = 30,
                           actorSystem:  ActorSystem,
                           materializer: Materializer) extends GraphStage[FlowShape[ResponseSidAndQoS, ResponseEnvelope]]{

  type Sid = Int

  val in = Inlet[ResponseSidAndQoS]("Subscriptions.in")
  val out = Outlet[ResponseEnvelope]("Subscriptions.out")
  implicit val as = actorSystem
  implicit val m = materializer

  override def shape: FlowShape[ResponseSidAndQoS, ResponseEnvelope] = FlowShape.of(in, out)


  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    val store = new HashMap[Sid, mutable.Queue[ResponseSidAndQoS]]
    val iter = store.iterator
    var downstreamWaiting = false

    def queue(key:Sid) = store.get(key).map(_.size).getOrElse(0)
    def shouldDislodge(key:Sid) = queue(key) < maxCapacity
    def pushToStore(value: ResponseSidAndQoS) = value match {
      case item @ ResponseSidAndQoS(_, _, QoS.Default) => store.put(value.sid, mutable.Queue(value))
      case item @ ResponseSidAndQoS(_, _, _) => {
        val queue = store.get(item.sid) map { q =>
          if(shouldDislodge(item.sid)) q.dequeue()
          q += value
        } getOrElse(mutable.Queue(value))

        store.put(item.sid, queue)
      }
    }

    override def preStart(): Unit = {
      // a detached stage needs to start upstream demand
      // itself as it is not triggered by downstream demand
      pull(in)
    }

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val elem = grab(in)
        pushToStore(elem)
        if(downstreamWaiting){
          downstreamWaiting = false
          pushNext
          pull(in)
        }
      }

      override def onUpstreamFinish(): Unit = {
        if (store.nonEmpty) {
          // emit the rest if possible
          emitMultiple(out, store.map{item => ResponseEnvelope(item._2.map(_.response))} toIterator)
        }
        completeStage()
      }
    })

    def pushNext = {

      //side effect
      var next = iter.map()
      while (next._2.isEmpty){
        store.remove(next._1)
        next = iter.next()
      }
      push(out, ResponseEnvelope(next._2))
      if(next._2.isEmpty) store.remove(next._1)
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if(store.isEmpty){
          downstreamWaiting = true
        } else {
          pushNext
        }

        if(!hasBeenPulled(in)) pull(in)
      }
    })
  }



}
