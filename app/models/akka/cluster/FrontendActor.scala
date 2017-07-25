package models.akka.cluster

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated, actorRef2Scala }
import akka.pattern.{ ask, pipe }
import akka.cluster.Cluster
import akka.util.Timeout

/**
 * Manages the broker fronted operations.
 */
class FrontendActor extends Actor with ActorLogging {
  import BackendActor._
  import FrontendActor._

  implicit val timeout = Timeout(5 seconds)
  import context.dispatcher

  private var backends = IndexedSeq.empty[ActorRef]

  private val cluster = Cluster(context.system)

  override def preStart() = {
    log.info("FrontendActor started at {}", self.path.toStringWithAddress(cluster.selfAddress))
  }

  override def postStop() = {
    log.info("FrontendActor stopped at {}", self.path.toStringWithAddress(cluster.selfAddress))
  }

  def receive = {
    case BackendRegistration if !backends.contains(sender) =>
      context watch sender
      backends = backends :+ sender
      log.info("{} added to the backend collection", sender.path)

    case Terminated(a) =>
      backends = backends.filterNot(_ == a)
      log.info("{} removed from backend collection", sender.path)

    case GetDSLinkCount => queryBackends[Int](GetDSLinkCount) pipeTo sender

    case FindDSLinks(regex, limit, offset) =>
      val results = queryBackends[Seq[String]](FindDSLinks(regex, Int.MaxValue, 0))
      val aggregated = results map (_.flatMap(_._2).toList)
      val truncated = aggregated map { list =>
        list.sorted.drop(offset).take(limit)
      }
      truncated pipeTo sender

    case GetClusterInfo => sender ! cluster.state
  }

  /**
   * Sends a message to all backends and collects the responses into a map.
   */
  private def queryBackends[T: ClassTag](request: Any) = {
    val results = backends map (ref => (ref ? request).mapTo[T].map(x => (ref -> x)))
    Future.sequence(results) map (_.toMap)
  }
}

/**
 * Messages for [[FrontendActor]].
 */
object FrontendActor {

  /**
   * Requests cluster information from the frontend, which returns an instance of
   * [[ClusterEvent.CurrentClusterState]].
   */
  case object GetClusterInfo

  /**
   * Creates a new instance of [[FrontendActor]] props.
   */
  def props = Props(new FrontendActor)
}