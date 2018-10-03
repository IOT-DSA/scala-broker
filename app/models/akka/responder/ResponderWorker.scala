package models.akka.responder

import akka.actor.{Actor, ActorLogging, Props, actorRef2Scala}
import akka.event.LoggingAdapter
import models.Origin
import models.akka.PartOfPersistenceBehavior
import models.rpc.DSAResponse

/**
 * A worker handling either List or Subscribe calls. Stores call records for multiple target Ids
 * (RIDs or SIDs).
 */
abstract class ResponderWorker(poolId: String) extends Actor with ActorLogging {
  import ResponderWorker._

  protected val ownId = s"Worker[$poolId-${math.abs(hashCode)}]"

  protected val registry: GroupCallRegistry

  def receive = {
    case LookupTargetId(origin)      => sender ! registry.lookupTargetId(origin)

    case AddOrigin(targetId, origin) => registry.addOrigin(targetId, origin, RegistryType.DEFAULT)

    case RemoveOrigin(origin)        => registry.removeOrigin(origin, RegistryType.DEFAULT)

    case RemoveAllOrigins(targetId) =>
      registry.remove(targetId, RegistryType.DEFAULT)
      log.info(s"$ownId: removed all bindings for $targetId")

    case GetOrigins(targetId)                => sender ! registry.getOrigins(targetId)

    case GetOriginCount(targetId)            => sender ! registry.getOrigins(targetId).size

    case GetLastResponse(targetId)           => sender ! registry.getLastResponse(targetId)

    case SetLastResponse(targetId, response) => registry.setLastResponse(targetId, response)
  }
}

/**
 * Messages for [[ResponderWorker]] actors.
 */
object ResponderWorker {
  case class LookupTargetId(origin: Origin)
  case class AddOrigin(targetId: Int, origin: Origin)
  case class RemoveOrigin(origin: Origin)
  case class RemoveAllOrigins(targetId: Int)
  case class GetOrigins(targetId: Int)
  case class GetOriginCount(targetId: Int)
  case class GetLastResponse(targetId: Int)
  case class SetLastResponse(targetId: Int, response: DSAResponse)
}

/**
 * Handles LIST calls by forwarding each response to its bound requesters.
 */
class ResponderListWorker(linkName: String) extends ResponderWorker(linkName + "/LST") {

  protected val registry = new ListCallRegistry(new ResponderWorkerPersistenceBehaviorStub(ownId, log))

  override def receive = super.receive orElse {
    case rsp @ DSAResponse(rid, stream, _, _, _) if rid != 0 =>
      log.info(s"$ownId: received $rsp")
      registry.deliverResponse(rsp)
  }
}

/**
 * Factory for [[ResponderListWorker]] instances.
 */
object ResponderListWorker {
  /**
   * Creates a new [[ResponderListWorker]] props instance.
   */
  def props(linkName: String) = Props(new ResponderListWorker(linkName))
}

/**
 * Handles SUBSCRIBE calls by forwarding each response to its bound requesters.
 */
class ResponderSubscribeWorker(linkName: String) extends ResponderWorker(linkName + "/SUB") {

  protected val registry = new SubscribeCallRegistry(new ResponderWorkerPersistenceBehaviorStub(ownId, log))

  override def receive = super.receive orElse {
    case rsp @ DSAResponse(0, stream, updates, columns, error) =>
      log.info(s"$ownId: received $rsp")
      registry.deliverResponse(rsp)
  }
}

/**
 * Factory for [[ResponderSubscribeWorker]] instances.
 */
object ResponderSubscribeWorker {
  /**
   * Creates a new [[ResponderSubscribeWorker]] props instance.
   */
  def props(linkName: String) = Props(new ResponderSubscribeWorker(linkName))
}

class ResponderWorkerPersistenceBehaviorStub(val _ownId: String, val _log: LoggingAdapter) extends PartOfPersistenceBehavior {
  override val ownId: String = _ownId
  override def persist[A](event: A)(handler: A => Unit): Unit = handler(event)
  override def onPersist: Unit = {}
  override def log: LoggingAdapter = _log
}
