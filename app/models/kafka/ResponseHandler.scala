package models.kafka

import org.apache.kafka.streams.processor.ProcessorContext

import models.ResponseEnvelope
import models.rpc.{ DSAMethod, DSAResponse, DSAValue, StreamState, extractSid, replaceSid }


/**
 * Handles responses coming from Web Socket.
 */
class ResponseHandler extends AbstractTransformer[String, ResponseEnvelope, String, Seq[ResponseEnvelope]] {

  private var ridRegistry: CallRegistry = null
  private var sidRegistry: CallRegistry = null
  private var attrStore: AttributeStore = null

  override def postInit(ctx: ProcessorContext) = {
    ridRegistry = BrokerFlow.RidManager.build(ctx)
    sidRegistry = BrokerFlow.SidManager.build(ctx)
    attrStore = AttributeStore.build(ctx)
  }

  /**
   * Splits the response updates in individual row, translates each update's SID into
   * (potentially) multiple source SIDs and creates one response per source SID.
   */
  private def handleSubscribeResponse: PartialFunction[(String, DSAResponse), Seq[(String, DSAResponse)]] = {
    case (target, rsp @ DSAResponse(0, stream, updates, columns, error)) =>
      val list = updates.getOrElse(Nil)
      if (list.isEmpty) {
        log.warn(s"Cannot find updates in Subscribe response $rsp")
        Nil
      } else list flatMap { row =>
        val targetSid = extractSid(row)
        val rec = sidRegistry.lookupByTargetId(target, targetSid).get
        if (stream == Some(StreamState.Closed))
          sidRegistry.removeLookup(target, rec)
        else
          sidRegistry.updateLookup(target, rec.withResponse(DSAResponse(0, stream, Some(List(row)), columns, error)))
        rec.origins map { origin =>
          val sourceRow = replaceSid(row, origin.sourceId)
          val response = DSAResponse(0, stream, Some(List(sourceRow)), columns, error)
          (origin.source, response)
        }
      }
  }

  /**
   * Handles a non-Subscribe response.
   */
  private val handleNonSubscribeResponse: PartialFunction[(String, DSAResponse), Seq[(String, DSAResponse)]] = {
    case (target, response) if response.rid != 0 =>
      val record = ridRegistry.lookupByTargetId(target, response.rid)
      record match {
        case None =>
          log.warn(s"did not find the route for $response")
          Nil
        case Some(rec) =>
          // adjust response for stored attributes, if appropriate
          val rsp = if (rec.method == DSAMethod.List) {
            val attrUpdates = attrStore.getAttributes(target, rec.path.get) map {
              case (name, value) => DSAValue.array(name, value)
            }
            val oldUpdates = response.updates getOrElse Nil
            response.copy(updates = Some(oldUpdates ++ attrUpdates))
          } else response

          if (rsp.stream == Some(StreamState.Closed))
            ridRegistry.removeLookup(target, rec)
          else
            ridRegistry.updateLookup(target, rec.withResponse(rsp))
          rec.origins map { origin =>
            (origin.source, rsp.copy(rid = origin.sourceId))
          } toSeq
      }
  }

  /**
   * Transforms the incoming response envelope into multiple envelopes (one per destination).
   */
  def transform(target: String, env: ResponseEnvelope) = {

    val handler = handleSubscribeResponse orElse handleNonSubscribeResponse

    val responses = env.responses flatMap { response =>
      val args = (target, response)
      handler(args)
    }

    val responsesBySource = responses.groupBy(_._1).mapValues(_.map(_._2))

    val envelopes = responsesBySource map {
      case (to, responses) => ResponseEnvelope(target, to, responses)
    }

    (target, envelopes.toSeq)
  }
}

/**
 * Factory for [[ResponseHandler]] instances.
 */
object ResponseHandler extends AbstractTransformerSupplier[String, ResponseEnvelope, String, Seq[ResponseEnvelope]] {

  /**
   * Stores required by [[RequestHandler]].
   */
  val StoresNames = BrokerFlow.RidManager.storeNames ++ BrokerFlow.SidManager.storeNames :+ AttributeStore.StoreName

  /**
   * Creates a new ResponsetHandler instance.
   */
  def get = new ResponseHandler
}