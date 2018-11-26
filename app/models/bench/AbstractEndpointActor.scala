package models.bench

import scala.concurrent.duration.{ Duration, FiniteDuration }

import org.joda.time.{ Duration => JodaDuration }

import AbstractEndpointActor.{ EndpointConfig, StatsTick }
import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, PoisonPill }
import akka.dispatch.{ BoundedMessageQueueSemantics, RequiresMessageQueue }
import akka.routing.Routee
import models.akka.{ ConnectionInfo, IntCounter, RichRoutee }
import models.akka.DSLinkMode.{ DSLinkMode, Dual, Requester, Responder }
import models.akka.Messages.ConnectEndpoint
import play.api.libs.json.{ Json, Reads, Writes }

/**
 * Base class for benchmark endpoint actors.
 */
abstract class AbstractEndpointActor(linkName: String, mode: DSLinkMode, routee: Routee,
                                     config: EndpointConfig) extends Actor with ActorLogging
  with RequiresMessageQueue[BoundedMessageQueueSemantics] {

  import context.dispatcher

  val isRequester = mode == Requester || mode == Dual
  val isResponder = mode == Responder || mode == Dual

  val connInfo = ConnectionInfo(linkName + "0" * 44, linkName, isRequester, isResponder,
    None, "1.1.2", List("json"))

  protected val localMsgId = IntCounter(1)

  private var statsJob: Option[Cancellable] = None

  /**
   * Registers endpoint and start stats collection loop.
   */
  override def preStart() = {
    routee ! ConnectEndpoint(connInfo, self)

    if (config.statsInterval > Duration.Zero)
      statsJob = Some(context.system.scheduler.schedule(config.statsInterval, config.statsInterval, self, StatsTick))

    log.info("[{}] started", linkName)
  }

  /**
   * Stops stats collection loop and unregisters the endpoint.
   */
  override def postStop() = {
    statsJob.foreach(_.cancel)

    routee ! PoisonPill
    log.info("[{}] stopped", linkName)
  }

  /**
   * Message processing loop.
   */
  def receive = {
    case StatsTick => reportStats
  }

  /**
   * Overridden by subsclasses to report stats data.
   */
  protected def reportStats(): Unit

  /**
   * Optionally converts the argument to JSON and back.
   */
  protected def viaJson[T, K >: T](obj: T)(implicit r: Reads[K], w: Writes[T]) = if (config.parseJson) {
    val json = Json.toJson(obj)
    json.as[K].asInstanceOf[T]
  } else obj
}

/**
 * Common types and methods for endpoint actors.
 */
object AbstractEndpointActor {

  /**
   * Endpoint actor configuration.
   */
  trait EndpointConfig {
    def statsInterval: FiniteDuration
    def statsCollector: Option[ActorRef]
    def parseJson: Boolean
  }

  /**
   * Base trait for statistical samples.
   */
  trait StatsSample {
    def duration: JodaDuration

    def ratePerSec(count: Int) = if (duration == JodaDuration.ZERO) 0.0 else count * 1000.0 / duration.getMillis
  }

  /**
   * Base for requester statistics.
   */
  trait ReqStatsBehavior extends StatsSample {
    def invokesSent: Int
    def updatesRcvd: Int

    def invokesSentPerSec = ratePerSec(invokesSent)
    def updatesRcvdPerSec = ratePerSec(updatesRcvd)
  }

  /**
   * Base for responder statistics.
   */
  trait RspStatsBehavior extends StatsSample {
    def invokesRcvd: Int
    def updatesSent: Int

    def invokesRcvdPerSec = ratePerSec(invokesRcvd)
    def updatesSentPerSec = ratePerSec(updatesSent)
  }

  /**
   * Sent by scheduler to initiate stats reporting.
   */
  case object StatsTick
}