package models.akka

import scala.concurrent.Future
import scala.reflect.ClassTag

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Status, Terminated, actorRef2Scala }
import akka.pattern.{ ask, pipe }
import akka.cluster.Cluster
import akka.util.Timeout
import models.{ RichBoolean, Settings }
import models.RequestEnvelope

/**
 * This actor is responsible for communicating with the facade. It can be deployed both in local
 * and cluster environments.
 */
class FrontendActor extends Actor with ActorLogging {
  import BackendActor._
  import Messages._
  import context.dispatcher

  if (self.path != self.path.root / "user" / "frontend") {
    val msg = "FrontendActor must be deployed under /user/frontend"
    log.error(new IllegalStateException(msg), msg + ", not " + self.path)
    context.system.terminate
  }

  implicit val timeout = Timeout(Settings.QueryTimeout)

  private val cluster = context.system.hasExtension(Cluster).option(Cluster(context.system))

  private var backends = IndexedSeq.empty[ActorRef]

  override def preStart() = log.info("FrontendActor started at {}", self.path.address)

  override def postStop() = log.info("FrontendActor stopped at {}", self.path.address)

  /**
   * Handles incoming messages.
   */
  def receive = receivePublic orElse receivePrivate

  /**
   * Handles public API messages.
   */
  def receivePublic: Receive = {
    case GetBrokerInfo =>
      val state = cluster map (_.state)
      sender ! BrokerInfo(backends.map(_.path), state)

    case GetDSLinkStats if backends.isEmpty =>
      noBackendsError()

    case GetDSLinkStats =>
      val nodeStats = askBackends[DSLinkNodeStats](GetDSLinkStats) map {
        _.values map (ns => ns.address -> ns)
      }
      nodeStats map (ns => DSLinkStats(ns.toMap)) pipeTo sender

    case FindDSLinks(regex, limit, offset) if backends.isEmpty =>
      noBackendsError()

    case msg @ FindDSLinks(regex, limit, offset) if backends.size == 1 =>
      backends.head ? msg pipeTo sender

    case FindDSLinks(regex, limit, offset) =>
      val results = askBackends[Seq[String]](FindDSLinks(regex, limit, offset))
      val aggregated = results map (_.flatMap(_._2).toList)
      val truncated = aggregated map { list =>
        list.sorted.drop(offset).take(limit)
      }
      truncated pipeTo sender

    case RemoveDisconnectedDSLinks if backends.isEmpty =>
      noBackendsError

    case RemoveDisconnectedDSLinks =>
      tellBackends(RemoveDisconnectedDSLinks)

    case RequestEnvelope(_) if backends.isEmpty =>
      noBackendsError

    case env: RequestEnvelope =>
      forwardToBackends(env)
  }

  /**
   * Handles internal messages.
   */
  def receivePrivate: Receive = {
    case RegisterBackend if !backends.contains(sender) =>
      context watch sender
      backends = backends :+ sender
      log.info("{} added to the backend collection", sender.path)
    case Terminated(a) =>
      backends = backends.filterNot(_ == a)
      log.info("{} removed from backend collection", sender.path)
  }

  /**
   * Sends a message to all backends and collects the responses into a map.
   */
  private def askBackends[T: ClassTag](request: Any) = {
    val results = backends map (ref => (ref ? request).mapTo[T].map(x => (ref -> x)))
    Future.sequence(results) map (_.toMap)
  }

  /**
   * Sends a message to all backends.
   */
  private def tellBackends(msg: Any) = backends foreach (_ ! msg)

  /**
   * Forwards a message to all backends.
   */
  private def forwardToBackends(msg: Any) = backends foreach (_.forward(msg))

  /**
   * Returns a failure to the sender in case no backends are available.
   */
  private def noBackendsError() = sender ! Status.Failure(new IllegalStateException("No unavailable backends"))
}

/**
 * Factory for [[FrontendActor]] instances.
 */
object FrontendActor {
  /**
   * Creates a new instance of [[FrontendActor]] props.
   */
  def props = Props(new FrontendActor)
}