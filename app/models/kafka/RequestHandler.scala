package models.kafka

import models._
import models.rpc._
import org.apache.kafka.streams.processor.ProcessorContext
import org.apache.kafka.streams.KeyValue

class RequestHandler extends AbstractTransformer[String, RequestEnvelope, String, RequestEnvelope] {

  var ridGen: IdGenerator = null
  var sidGen: IdGenerator = null

  override def postInit(ctx: ProcessorContext) = {
    ridGen = new IdGenerator(ctx.getKeyValueStore("RidGenerator"), 1)
    sidGen = new IdGenerator(ctx.getKeyValueStore("SidGenerator"), 1)
  }

  private val handlePassthroughRequest: PartialFunction[(DSARequest, String), (String, DSARequest)] = {
    case (SetRequest(rid, path, value, permit), target) =>
      val targetRid = ridGen.inc(target)
      (target, SetRequest(rid, translatePath(path, target), value, permit))
    case (RemoveRequest(rid, path), target) =>
      val targetRid = ridGen.inc(target)
      (target, RemoveRequest(targetRid, translatePath(path, target)))
    case (InvokeRequest(rid, path, params, permit), target) =>
      val targetRid = ridGen.inc(target)
      (target, InvokeRequest(targetRid, translatePath(path, target), params, permit))
    case (ListRequest(rid, path), target) =>
      val targetRid = ridGen.inc(target)
      (target, ListRequest(targetRid, translatePath(path, target)))
  }

  def transform(target: String, env: RequestEnvelope) = {
    val results = env.requests map { req =>
      val tuple = (req, target)
      handlePassthroughRequest(tuple)
    }
    // TODO temporary, just to make it compile
    (results.head._1, RequestEnvelope(env.from, results.head._1, List(results.head._2)))
  }

  /**
   * Removes the linkPath prefix from the path.
   */
  private def translatePath(path: String, linkPath: String) = {
    val chopped = path.drop(linkPath.size)
    if (chopped.isEmpty) "/" else chopped
  }
}

object RequestHandler extends AbstractTransformerSupplier[String, RequestEnvelope, String, RequestEnvelope] {
  def get = new RequestHandler
}