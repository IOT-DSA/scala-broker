package models.bench

import scala.concurrent.duration.FiniteDuration

import BenchmarkRequester.BenchmarkRequesterConfig
import akka.actor.Props
import models.akka.{ CommProxy, DSLinkMode, IntCounter }
import models.rpc._

/**
 * Simulates a Requester that subscribes to a responder's node updates and then repeatedly
 * calls the action that causes that node to be updated
 */
class BenchmarkRequester(linkName: String, proxy: CommProxy, config: BenchmarkRequesterConfig)
  extends AbstractEndpointActor(linkName, DSLinkMode.Requester, proxy) {

  import context.dispatcher

  private val ridGen = new IntCounter(1)

  override def preStart() = {
    super.preStart

    // subscribe to path events
    val subReq = SubscribeRequest(ridGen.inc, SubscriptionPath(config.path, 101))
    proxy ! RequestMessage(localMsgId.inc, None, List(subReq))
    log.info("Requester[{}] subscribed to [{}]", linkName, config.path)

    // schedule action invocation
    val invPath = config.path + "/incCounter"
    context.system.scheduler.schedule(config.timeout, config.timeout) {
      (1 to config.batchSize) foreach { _ =>
        val req = InvokeRequest(ridGen.inc, invPath)
        proxy ! RequestMessage(localMsgId.inc, None, List(req))
      }
      log.debug("Requester[{}]: sent a batch of {} InvokeRequests to {}", linkName, config.batchSize, config.path)
    }
  }

  override def postStop() = {
    // unsubscribe from path
    val unsReq = UnsubscribeRequest(ridGen.inc, List(101))
    proxy ! RequestMessage(localMsgId.inc, None, List(unsReq))
    log.info("Requester[{}] unsubscribed from [{}]", linkName, config.path)

    super.postStop
  }

  def receive = {
    case msg => log.warning("[{}]: received unknown message - {}", msg)
  }
}

/**
 * Factory for [[BenchmarkRequester]] instances.
 */
object BenchmarkRequester {
  /**
   * BenchmarkRequester configuration.
   */
  case class BenchmarkRequesterConfig(path: String, batchSize: Int, timeout: FiniteDuration)

  /**
   * Creates a new [[BenchmarkRequester]] props instance.
   */
  def props(linkName: String, proxy: CommProxy, config: BenchmarkRequesterConfig) =
    Props(new BenchmarkRequester(linkName, proxy, config))
}