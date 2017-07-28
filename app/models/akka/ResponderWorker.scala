package models.akka

import akka.actor.{ Actor, ActorLogging, Props, actorRef2Scala }
import models.{ Origin, ResponseEnvelope }
import models.rpc._
import models.rpc.DSAValue.DSAVal
import models.rpc.StreamState.StreamState

/**
 * Encapsulates information about a List or Subscribe call - the requesters assigned to it
 * and the last received response, if any.
 */
class WorkerCallRecord {
  private var _origins = Set.empty[Origin]
  private var _lastResponse: Option[DSAResponse] = None

  def lastResponse = _lastResponse
  def setLastResponse(rsp: DSAResponse) = { _lastResponse = Some(rsp); this }

  def origins = _origins
  def addOrigin(origin: Origin) = { _origins += origin; this }
  def removeOrigin(origin: Origin) = { _origins -= origin; this }
}

/**
 * A worker handling either List or Subscribe calls. Storer call records for multiple target Ids
 * (RIDs or SIDs).
 */
abstract class ResponderWorker(poolId: String) extends Actor with ActorLogging {
  import ResponderWorker._

  protected val ownId = s"Worker[$poolId-${math.abs(hashCode)}]"

  protected val bindings = collection.mutable.Map.empty[Int, WorkerCallRecord]

  def receive = {
    case LookupTargetId(origin) =>
      sender ! bindings.find(_._2.origins.contains(origin)).map(_._1)
      
    case AddOrigin(targetId, origin) =>
      val record = bindings.getOrElseUpdate(targetId, new WorkerCallRecord).addOrigin(origin)
      log.debug(s"$ownId: added binding $targetId -> $origin")
      onAddOrigin(targetId, origin, record)

    case RemoveOrigin(origin) => bindings.find(_._2.origins.contains(origin)) foreach {
      case (targetId, record) =>
        record.removeOrigin(origin)
        log.debug(s"$ownId: removed binding $targetId -> $origin")
        if (record.origins.isEmpty) {
          bindings -= targetId
          log.info(s"$ownId: removed entry for Id: $targetId")
        }
    }

    case RemoveAllOrigins(targetId) =>
      bindings -= targetId
      log.info(s"$ownId: removed all bindings for $targetId")

    case GetOrigins(targetId) =>
      sender ! bindings.get(targetId).map(_.origins).getOrElse(Set.empty)

    case GetOriginCount(targetId) =>
      sender ! bindings.get(targetId).map(_.origins.size).getOrElse(0)

    case GetLastResponse(targetId) =>
      sender ! bindings.get(targetId).flatMap(_.lastResponse)

    case SetLastResponse(targetId, response) =>
      bindings.getOrElseUpdate(targetId, new WorkerCallRecord).setLastResponse(response)
  }

  /**
   * Can be implemented by subclasses, called when an origin is added to a binding.
   */
  protected def onAddOrigin(targetId: Int, origin: Origin, record: WorkerCallRecord) = {}
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

  override def receive = super.receive orElse {
    case rsp @ DSAResponse(rid, stream, _, _, _) if rid != 0 =>
      log.info(s"$ownId: received $rsp")
      val rec = bindings.getOrElseUpdate(rid, new WorkerCallRecord)
      rec.setLastResponse(rsp)
      rec.origins foreach { origin =>
        val response = rsp.copy(rid = origin.sourceId)
        origin.source ! ResponseEnvelope(List(response))
      }
      if (stream == Some(StreamState.Closed)) // shouldn't normally happen w/o CLOSE
        bindings -= rid
  }

  override protected def onAddOrigin(targetId: Int, origin: Origin, record: WorkerCallRecord) =
    record.lastResponse foreach { response =>
      origin.source ! ResponseEnvelope(List(response.copy(rid = origin.sourceId)))
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

  override def receive = super.receive orElse {
    case rsp @ DSAResponse(0, stream, updates, columns, error) =>
      log.info(s"$ownId: received $rsp")
      val list = updates.getOrElse(Nil)
      if (list.isEmpty) {
        log.warning(s"$ownId: cannot find updates in Subscribe response $rsp")
      } else {
        val results = list flatMap handleSubscribeResponseRow(stream, columns, error)
        results groupBy (_._1) mapValues (_.map(_._2)) foreach {
          case (to, rsps) => to ! ResponseEnvelope(rsps)
        }
      }
  }

  private def handleSubscribeResponseRow(stream: Option[StreamState],
                                         columns: Option[List[ColumnInfo]],
                                         error: Option[DSAError])(row: DSAVal) = {
    val targetSid = extractSid(row)

    val rec = bindings.getOrElseUpdate(targetSid, new WorkerCallRecord)
    rec.setLastResponse(DSAResponse(0, stream, Some(List(row)), columns, error))

    if (stream == Some(StreamState.Closed)) // shouldn't normally happen w/o UNSUBSCRIBE
      bindings -= targetSid

    rec.origins map { origin =>
      val sourceRow = replaceSid(row, origin.sourceId)
      val response = DSAResponse(0, stream, Some(List(sourceRow)), columns, error)
      (origin.source, response)
    }
  }

  override protected def onAddOrigin(targetId: Int, origin: Origin, record: WorkerCallRecord) =
    record.lastResponse foreach { rsp =>
      val sourceRow = replaceSid(rsp.updates.get.head, origin.sourceId)
      val response = DSAResponse(0, rsp.stream, Some(List(sourceRow)), rsp.columns, rsp.error)
      origin.source ! ResponseEnvelope(List(response))
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