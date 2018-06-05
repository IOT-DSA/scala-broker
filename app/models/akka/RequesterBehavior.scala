package models.akka

import akka.actor.ActorRef
import akka.actor.Status.Success
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import models.akka.Messages._
import models.metrics.Meter
import scala.util.control.NonFatal
import models.{RequestEnvelope, ResponseEnvelope, Settings, SubscriptionResponseEnvelope}
import models.rpc._
import models.rpc.DSAValue.DSAVal
import org.reactivestreams.Publisher
import models.akka.QoSState._

/**
 * Handles communication with a remote DSLink in Requester mode.
 */
trait RequesterBehavior { me: AbstractDSLinkActor with Meter =>

  implicit val materializer = ActorMaterializer()

  protected def dslinkMgr: DSLinkManager

  //state actore to store different dslink state with persistance etc
  val qosState = context.actorOf(QoSState.props(
    reconnectionTime = Settings.Subscriptions.reconnectionTimeout,
    maxCapacity = Settings.Subscriptions.queueCapacity
  ), "stateKeeper")

  // used by Close and Unsubscribe requests to retrieve the targets of previously used RID/SID
  private val targetsByRid = collection.mutable.Map.empty[Int, String]
  private val targetsBySid = collection.mutable.Map.empty[Int, PathAndQos]

  private var lastRid: Int = 0

  val channel = Flow.fromGraph(new SubscriptionChannel(qosState))

  var toSocket: SourceQueueWithComplete[SubscriptionNotificationMessage] = _
  var subscriptionsPublisher: Publisher[DSAMessage] = _

  /**
   * Processes incoming messages from Requester DSLink and dispatches responses to it.
   */
  val requesterBehavior: Receive = {
    case m @ RequestMessage(msg, ack, requests) =>
      log.debug("{}: received {}", ownId, m)
      processRequests(requests)
      requests.lastOption foreach ( req => persist(LastRidSet(req.rid)) (event => lastRid = event.rid) )
    case e @ ResponseEnvelope(responses) =>
      log.debug("{}: received {}", ownId, e)
      cleanupStoredTargets(responses)

      val(subscriptions, other) = responses.partition(isSubscription)

      if(subscriptions.nonEmpty){
        log.debug("handle subscriptions: {}", subscriptions)
        handleSubscriptions(subscriptions)
      }

      if(other.nonEmpty){
        log.debug("send to endpoint other: {}", other)
        sendToEndpoint(ResponseEnvelope(other))
      }

    case SubscriptionSourceMessage(actorRef) =>
      getSubscriptionSource(actorRef)

  }

  // requester mixin for disconnected behavior
  val requesterDisconnected: Receive = {
    case SubscriptionSourceMessage(actorRef) =>
      getSubscriptionSource(actorRef)
    case e @ ResponseEnvelope(responses) =>

      log.debug("{}: received {}", ownId, e)
      cleanupStoredTargets(responses)

      val(subscriptions, other) = responses.partition(isSubscription)

      if(subscriptions.nonEmpty){
        log.debug("handle subscriptions: {}", subscriptions)
        handleSubscriptions(subscriptions, false)
      }

      if(other.nonEmpty){
        log.debug("stashing other: {}", other)
        stash()
      }
  }

  override protected def afterConnection(): Unit = {
    qosState ! Connected
  }

  override protected def afterDisconnection(): Unit = {
    qosState ! Disconnected
  }

  /**
    * @return stream subscriptions stream publisher
    */
  private def getSubscriptionSource(actor:ActorRef) = {

    log.info("new subscription source connected: {}", actor)

    val (toSocketVal, publisher) = Source.queue(100, OverflowStrategy.backpressure)
      .via(channel).toMat(Sink.actorRef(actor, Success(())))(Keep.both).run()

    toSocket = toSocketVal
  }

  /**
    * Recovers events of requester behavior from the journal.
    */
  val requesterRecover: Receive = {
    case event: RidTargetsRequesterState =>
      log.debug("{}: trying to recover {}", ownId, event)
      targetsByRid.put(event.rid, event.target)
    case event: SidTargetsRequesterState =>
      log.debug("{}: trying to recover {}", ownId, event)
      targetsBySid.put(event.sid, event.pathAndQos)
    case event: RemoveTargetByRid =>
      log.debug("{}: trying to recover remove action by {}", ownId, event)
      targetsByRid.remove(event.rid)
    case event: RemoveTargetBySid =>
      log.debug("{}: trying to recover remove action by {}", ownId, event)
      targetsBySid.remove(event.sid)
    case event: LastRidSet =>
      log.debug("{}: trying to recover {}", ownId, event)
      lastRid = event.rid
  }

  /**
   * Sends Unsubscribe for all open subscriptions and Close for List commands.
   */
  def stopRequester() = {
    batchAndRoute(targetsByRid map { case (rid, target) => target -> CloseRequest(rid) })
    batchAndRoute(targetsBySid.zipWithIndex map {
      case ((sid, PathAndQos(target, _)), index) => target -> UnsubscribeRequest(lastRid + index + 1, sid)
    })
  }

  private def handleSubscriptions(subscriptions:Seq[DSAResponse], connected: Boolean = true) = subscriptions foreach {
    r => withQosAndSid(r) foreach {
      message =>
      log.debug("sending subscription message: {}", message)
      val toSend = SubscriptionNotificationMessage(-1, None, List(message.response), message.sid, message.qos)

      if(connected){
        // in connected state pushing to stream with backpressure logic
        toSocket offer toSend
      } else if(message.qos >= QoS.Durable){
        //in disconnected - just send to state actor
        qosState ! PutNotification(toSend)
      }
    }
  }

  private def isSubscription(response:DSAResponse):Boolean = response.rid == 0

  private def withQosAndSid(response:DSAResponse):Seq[SubscriptionResponseEnvelope] = {
    response.updates.map{
      _.map{
          update =>
            val sid = extractSid(update)
            val qos = targetsBySid.get(sid).map(_.qos) getOrElse(QoS.Default)
            SubscriptionResponseEnvelope(response, sid, qos)
        }
    } getOrElse(List())
  }

  /**
   * Processes and routes requests.
   */
  private def processRequests(requests: Iterable[DSARequest]) = {

    def splitRequest(request: DSARequest) = request match {
      case req: SubscribeRequest   => req.split
      case req: UnsubscribeRequest => req.split
      case req @ _                 => req :: Nil
    }

    val results = requests flatMap splitRequest flatMap (request => try {
      val target = resolveTarget(request)
      cacheRequestTarget(request, target)
      List(target -> request)
    } catch {
      case NonFatal(_) => log.error("{}: RID/SID not found for {}", ownId, request); Nil
    })

    log.debug("{}: RID targets: {}, SID targets: {}", ownId, targetsByRid.size, targetsBySid.size)

    batchAndRoute(results)
  }

  /**
   * Removes stored targets when the response's stream is closed.
   */
  private def cleanupStoredTargets(responses: Seq[DSAResponse]) = {

    def cleanupSids(rows: Seq[DSAVal]) = try {
        for (r <- rows)
          persist(RemoveTargetBySid(extractSid(r))) { event =>
            log.debug("{}: removing by SID persisted {}", ownId, event)
            targetsBySid.remove(event.sid)
          }
    } catch {
      case NonFatal(_) => log.error("{}: subscribe response does not have a valid SID", ownId)
    }

    responses filter (_.stream == Some(StreamState.Closed)) foreach {
      case DSAResponse(0, _, Some(updates), _, _)   => cleanupSids(updates)
      case DSAResponse(rid, _, _, _, _) if rid != 0 =>
        persist(RemoveTargetByRid(rid)) { event =>
          log.debug("{}: removing by RID persisted {}", ownId, event)
          targetsByRid.remove(event.rid)
        }
    }

    log.debug("{}: RID targets: {}, SID targets: {}", ownId, targetsByRid.size, targetsBySid.size)
  }

  /**
   * Groups the requests by their target and routes each batch as one envelope.
   */
  private def batchAndRoute(requests: Iterable[(String, DSARequest)]) = {
    requests groupBy (_._1) mapValues (_.map(_._2)) foreach {
      case (to, reqs) =>
        val envelope = RequestEnvelope(reqs.toSeq)
        log.debug("{}: sending {} to [{}]", ownId, envelope, to)
        dslinkMgr.dsaSend(to, envelope)
        logRequestBatch(to, envelope.requests)
    }
  }

  /**
   * Logs requests.
   */
  private def logRequestBatch(to: String, requests: Seq[DSARequest]) = {
    import models.Settings.Paths._

    val tgtLinkName = if (to.startsWith(Downstream) && to != Downstream)
      to drop Downstream.size + 1
    else
      "broker"

    meterTags(tagsForConnection("out.requests.batch")(connInfo):_*)
    incrementTagsNTimes(tagsForConnection("out.requests.batch.requests")(connInfo):_*)(requests.size)

  }

  /**
   * Saves the request's target indexed by its RID or SID, where applicable.
   */
  private def cacheRequestTarget(request: DSARequest, target: String) = request match {
    case r @ (_: ListRequest | _: InvokeRequest) =>
      persist(RidTargetsRequesterState(r.rid, target)) { event =>
        log.debug("{}: RID targets persisted {}", ownId, event)
        targetsByRid.put(event.rid, event.target)
      }
    case r: SubscribeRequest =>
      persist(SidTargetsRequesterState(r.path.sid, PathAndQos(target, r.path.qos.map(QoS(_)).getOrElse(QoS.Default)))) { event =>
        log.debug("{}: SID targets persisted {}", ownId, event)
        targetsBySid.put(event.sid, event.pathAndQos)
      }
    case _ => // do nothing
  }

  /**
   * Resolves target link by analyzing the path.
   */
  private val resolveTargetByPath = {

    val extractPath: PartialFunction[DSARequest, String] = {
      case ListRequest(_, path)         => path
      case SetRequest(_, path, _, _)    => path
      case RemoveRequest(_, path)       => path
      case InvokeRequest(_, path, _, _) => path
      case SubscribeRequest(_, paths)   => paths.head.path // assuming one path after split
    }

    extractPath andThen resolveLinkPath
  }

  /**
   * Tries to resolve the request target by path or by cached RID/SID (for Close/Unsubscribe, and
   * also removes the target from the cache after the look up).
   */
  private def resolveTarget(request: DSARequest) = {

    val resolveUnsubscribeTarget: PartialFunction[DSARequest, String] = {
      case UnsubscribeRequest(_, sids) =>
        val target = targetsBySid.get(sids.head).get.path
        persist(RemoveTargetBySid(sids.head)) { event =>
          log.debug("{}: removing by SID persisted {}", ownId, event)
          targetsBySid.remove(event.sid)
        }
        target
    }

    val resolveCloseTarget: PartialFunction[DSARequest, String] = {
      case CloseRequest(rid) =>
        val target = targetsByRid.get(rid).get
        persist(RemoveTargetByRid(rid)) { event =>
          log.debug("{}: removing by RID persisted {}", ownId, event)
          targetsByRid.remove(event.rid)
        }
        target
    }

    (resolveTargetByPath orElse resolveUnsubscribeTarget orElse resolveCloseTarget)(request)
  }
}

case class PathAndQos(path:String, qos:QoS.Level)

object QoS {
  sealed abstract class Level(val index:Int){
    def <(other:Level) = index < other.index
    def >(other:Level) = index > other.index
    def >=(other:Level) = index >= other.index
    def <=(other:Level) = index <= other.index
  }

  case object Default extends Level(0)
  case object Queued extends Level(1)
  case object Durable extends Level(2)
  case object DurableAndPersist extends Level(3)


  def apply(level:Int): QoS.Level = level match {
    case 0 => Default
    case 1 => Queued
    case 2 => Durable
    case 3 => DurableAndPersist
    case  _ => throw new RuntimeException(s"unsupported QoS level: $level")
  }
}