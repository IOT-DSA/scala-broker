package models.akka.responder

import scala.concurrent.duration.DurationInt
import akka.actor._
import akka.persistence.PersistentActor
import akka.routing.{Broadcast, ConsistentHashingPool}
import persistence.{MainResponderBehaviorState, PooledResponderBehaviorState, ResponderBehaviorState}
import models.{Origin, Settings}
import models.rpc.DSAResponse

/**
 * Handles communication with a remote DSLink in Responder mode using a router and a pool of workers
 * for implementing multi-recipient responce delivery (LIST, SUBSCRIBE).
 */
trait PooledResponderBehavior extends ResponderBehavior { me: PersistentActor with ActorLogging =>

  import context.system
  import ResponderWorker._
  import akka.routing.ConsistentHashingRouter._
  
  protected def linkName: String

  // LIST and SUBSCRIBE workers
  private val originHash: ConsistentHashMapping = {
    case AddOrigin(_, origin)   => origin
    case RemoveOrigin(origin)   => origin
    case LookupTargetId(origin) => origin
  }

  private var listPool = ConsistentHashingPool(Settings.Responder.ListPoolSize, hashMapping = originHash)
  private val listRouter = context.actorOf(listPool.props(ResponderListWorker.props(linkName)))

  private var subsPool = ConsistentHashingPool(Settings.Responder.SubscribePoolSize, hashMapping = originHash)
  private val subsRouter = context.actorOf(subsPool.props(ResponderSubscribeWorker.props(linkName)))

  val pooledResponderRecover: Receive = {
    case offeredSnapshot: PooledResponderBehaviorState =>
      log.debug("{}: recovering with snapshot {}", ownId, offeredSnapshot)
      listPool = offeredSnapshot.listPool
      subsPool = offeredSnapshot.subsPool
  }

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
    val ibox = Inbox.create(system)
    router.tell(LookupTargetId(origin), ibox.getRef)
    ibox.receive(10 seconds).asInstanceOf[Option[Int]] map { targetId =>
      router ! RemoveOrigin(origin)
      router.tell(Broadcast(GetOriginCount(targetId)), ibox.getRef)
      val count = (1 to pool.nrOfInstances).map(_ => ibox.receive(10 seconds).asInstanceOf[Int]).sum
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

  /**
    * Tries to save this responder state as a snapshot.
    */
  protected def saveResponderBehaviorSnapshot(main: MainResponderBehaviorState) =
    saveSnapshot(ResponderBehaviorState(main, PooledResponderBehaviorState(listPool, subsPool)))
}
