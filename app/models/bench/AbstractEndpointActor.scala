package models.bench

import scala.concurrent.duration.{ Duration, FiniteDuration }

import AbstractEndpointActor.{ EndpointConfig, StatsTick }
import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable }
import models.akka.{ CommProxy, ConnectionInfo, IntCounter }
import models.akka.DSLinkMode.{ DSLinkMode, Dual, Requester, Responder }
import models.akka.Messages.{ ConnectEndpoint, DisconnectEndpoint }
import org.joda.time.{ Duration => JodaDuration }

/**
 * Base class for benchmark endpoint actors.
 */
abstract class AbstractEndpointActor(linkName: String, mode: DSLinkMode, proxy: CommProxy,
                                     config: EndpointConfig) extends Actor with ActorLogging {

  import context.dispatcher

  val isRequester = mode == Requester || mode == Dual
  val isResponder = mode == Responder || mode == Dual

  val connInfo = ConnectionInfo(linkName + "0" * 44, linkName, isRequester, isResponder)

  protected val localMsgId = new IntCounter(1)

  private var statsJob: Option[Cancellable] = None

  /**
   * Registers endpoint and start stats collection loop.
   */
  override def preStart() = {
    proxy tell ConnectEndpoint(self, connInfo)

    if (config.statsInterval > Duration.Zero)
      statsJob = Some(context.system.scheduler.schedule(config.statsInterval, config.statsInterval, self, StatsTick))

    log.info("[{}] started", linkName)
  }

  /**
   * Stops stats collection loop and unregisters the endpoint.
   */
  override def postStop() = {
    statsJob.foreach(_.cancel)

    proxy tell DisconnectEndpoint
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