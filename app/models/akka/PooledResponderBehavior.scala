package models.akka

import akka.actor.{ ActorRef, actorRef2Scala }
import akka.routing.{ Broadcast, ConsistentHashingPool }
import models.{ Origin, Settings }
import models.rpc.DSAResponse

/**
 * Handles communication with a remote DSLink in Responder mode.
 */
trait PooledResponderBehavior extends ResponderBehavior { me: AbstractDSLinkActor =>
  import context.system
  import ResponderWorker._
  import akka.actor.ActorDSL._
  import akka.routing.ConsistentHashingRouter._

  // LIST and SUBSCRIBE workers
  private val originHash: ConsistentHashMapping = {
    case AddOrigin(_, origin)   => origin
    case RemoveOrigin(origin)   => origin
    case LookupTargetId(origin) => origin
  }

  private val listPool = ConsistentHashingPool(Settings.Responder.ListPoolSize, hashMapping = originHash)
  private val listRouter = context.actorOf(listPool.props(ResponderListWorker.props(linkName)))

  private val subsPool = ConsistentHashingPool(Settings.Responder.SubscribePoolSize, hashMapping = originHash)
  private val subsRouter = context.actorOf(subsPool.props(ResponderSubscribeWorker.props(linkName)))

  /**
   * Sends `AddOrigin` message to the list router.
   */
  protected def addListOrigin(targetId: Int, origin: Origin) = listRouter ! AddOrigin(targetId, origin)

  /**
   * Sends `AddOrigin` message to the subs router.
   */
  protected def addSubscribeOrigin(targetId: Int, origin: Origin) = subsRouter ! AddOrigin(targetId, origin)

  /**
   * Sends `RemoveOrigin` to LIST workers, then broadcasts `GetOriginCount` and collects the results.
   */
  protected def removeListOrigin(origin: Origin) = removeOrigin(origin, listPool, listRouter)

  /**
   * Sends `RemoveOrigin` to SUBSCRIBE workers, then broadcasts `GetOriginCount` and collects the results.
   */
  protected def removeSubscribeOrigin(origin: Origin) = removeOrigin(origin, subsPool, subsRouter)

  /**
   * Removes the origin from one of the workers. Returns `Some(targetId)` if the entry can be
   * removed (i.e. no listeners left), or None otherwise.
   */
  private def removeOrigin(origin: Origin, pool: ConsistentHashingPool, router: ActorRef) = {
    val ibox = inbox()
    router.tell(LookupTargetId(origin), ibox.getRef)
    ibox.receive().asInstanceOf[Option[Int]] map { targetId =>
      router ! RemoveOrigin(origin)
      router.tell(Broadcast(GetOriginCount(targetId)), ibox.getRef)
      val count = (1 to pool.nrOfInstances).map(_ => ibox.receive().asInstanceOf[Int]).sum
      (targetId, count < 1)
    } collect {
      case (targetId, true) => targetId
    }
  }

  /**
   * Broadcasts the response to the subscriber router's workers.
   */
  protected def deliverListResponse(rsp: DSAResponse) = listRouter ! Broadcast(rsp)

  /**
   * Broadcasts the response to the subscriber router's workers.
   */
  protected def deliverSubscribeResponse(rsp: DSAResponse) = subsRouter ! Broadcast(rsp)
}