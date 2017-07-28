package models.akka

import scala.util.control.NonFatal

import akka.actor.{ ActorRef, actorRef2Scala }
import models._
import models.rpc._
import models.rpc.DSAMethod.DSAMethod
import models.rpc.DSAValue.{ DSAVal, StringValue, array }

/**
 * Handles communication with a remote DSLink in Responder mode.
 */
trait SimpleResponderBehavior extends ResponderBehavior { me: AbstractDSLinkActor =>
  import StreamState._

  protected var listBindings = Map.empty[Int, WorkerCallRecord]
  protected var subsBindings = Map.empty[Int, WorkerCallRecord]

  /**
   * Adds the origin to the list of recipients for the given target RID.
   */
  protected def addListOrigin(targetId: Int, origin: Origin) = {
    val record = listBindings.getOrElse(targetId, {
      val wcr = new WorkerCallRecord
      listBindings += targetId -> wcr
      wcr
    })
    record.addOrigin(origin)
    log.debug(s"$ownId: added LIST binding $targetId -> $origin")
    record.lastResponse foreach { response =>
      origin.source ! ResponseEnvelope(List(response.copy(rid = origin.sourceId)))
    }
  }

  /**
   * Adds the origin to the list of recipients for the given target SID.
   */
  protected def addSubscribeOrigin(targetId: Int, origin: Origin) = {
    val record = subsBindings.getOrElse(targetId, {
      val wcr = new WorkerCallRecord
      subsBindings += targetId -> wcr
      wcr
    })
    record.addOrigin(origin)
    log.debug(s"$ownId: added SUBSCRIBE binding $targetId -> $origin")
    record.lastResponse foreach { rsp =>
      val sourceRow = replaceSid(rsp.updates.get.head, origin.sourceId)
      val response = DSAResponse(0, rsp.stream, Some(List(sourceRow)), rsp.columns, rsp.error)
      origin.source ! ResponseEnvelope(List(response))
    }
  }

  /**
   * Removes the origin from the collection of LIST recipients it belongs to. Returns `Some(targetId)`
   * if the call record can be removed (i.e. no listeners left), or None otherwise.
   */
  protected def removeListOrigin(origin: Origin) = listBindings.find(_._2.origins contains origin) flatMap {
    case (targetId, record) =>
      record.removeOrigin(origin)
      if (record.origins isEmpty) {
        listBindings -= targetId
        Some(targetId)
      } else None
  }

  /**
   * Removes the origin from the collection of SUBSCRIBE recipients it belongs to. Returns `Some(targetId)`
   * if the call record can be removed (i.e. no listeners left), or None otherwise.
   */
  protected def removeSubscribeOrigin(origin: Origin) = subsBindings.find(_._2.origins contains origin) flatMap {
    case (targetId, record) =>
      record.removeOrigin(origin)
      if (record.origins isEmpty) {
        subsBindings -= targetId
        Some(targetId)
      } else None
  }

  /**
   * Delivers a LIST response to its recipients.
   */
  protected def deliverListResponse(rsp: DSAResponse) = {
    val record = listBindings.getOrElse(rsp.rid, {
      val wcr = new WorkerCallRecord
      listBindings += rsp.rid -> wcr
      wcr
    })
    record.setLastResponse(rsp)
    record.origins foreach { origin =>
      val response = rsp.copy(rid = origin.sourceId)
      origin.source ! ResponseEnvelope(List(response))
    }
    if (rsp.stream == Some(StreamState.Closed)) // shouldn't normally happen w/o CLOSE
      listBindings -= rsp.rid
  }

  /**
   * Delivers a SUBSCRIBE response to its recipients.
   */
  protected def deliverSubscribeResponse(rsp: DSAResponse) = {
    val list = rsp.updates.getOrElse(Nil)
    if (list.isEmpty) {
      log.warning(s"$ownId: cannot find updates in Subscribe response $rsp")
    } else {
      val results = list flatMap handleSubscribeResponseRow(rsp.stream, rsp.columns, rsp.error)
      results groupBy (_._1) mapValues (_.map(_._2)) foreach {
        case (to, rsps) => to ! ResponseEnvelope(rsps)
      }
    }
  }

  private def handleSubscribeResponseRow(stream: Option[StreamState],
                                         columns: Option[List[ColumnInfo]],
                                         error: Option[DSAError])(row: DSAVal) = {
    val targetSid = extractSid(row)

    val rec = subsBindings.getOrElse(targetSid, {
      val wcr = new WorkerCallRecord
      subsBindings += targetSid -> wcr
      wcr
    })
    rec.setLastResponse(DSAResponse(0, stream, Some(List(row)), columns, error))

    if (stream == Some(StreamState.Closed)) // shouldn't normally happen w/o UNSUBSCRIBE
      subsBindings -= targetSid

    rec.origins map { origin =>
      val sourceRow = replaceSid(row, origin.sourceId)
      val response = DSAResponse(0, stream, Some(List(sourceRow)), columns, error)
      (origin.source, response)
    }
  }
}