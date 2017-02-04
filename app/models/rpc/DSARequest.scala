package models.rpc

import DSAValue._

/**
 * Supported DSA methods.
 */
object DSAMethod extends Enumeration {
  type DSAMethod = Value

  val List = Value("list")
  val Set = Value("set")
  val Remove = Value("remove")
  val Invoke = Value("invoke")
  val Subscribe = Value("subscribe")
  val Unsubscribe = Value("unsubscribe")
  val Close = Value("close")
}
import DSAMethod._

/**
 * Base trait for DSA requests.
 */
sealed trait DSARequest extends Serializable {
  val rid: Int
  val method: DSAMethod
}

/**
 * LIST request.
 */
case class ListRequest(rid: Int, path: String) extends DSARequest {
  val method = List
}

/**
 * SET request.
 */
case class SetRequest(rid: Int, path: String, value: DSAVal, permit: Option[String] = None) extends DSARequest {
  val method = Set
}

/**
 * REMOVE request.
 */
case class RemoveRequest(rid: Int, path: String) extends DSARequest {
  val method = Remove
}

/**
 * INVOKE request.
 */
case class InvokeRequest(rid: Int, path: String, params: DSAMap = Map.empty,
                         permit: Option[String] = None) extends DSARequest {
  val method = Invoke
}

/**
 * SUBSCRIBE request.
 */
case class SubscriptionPath(path: String, sid: Int, qos: Option[Int] = None)
case class SubscribeRequest(rid: Int, paths: List[SubscriptionPath]) extends DSARequest {
  val method = Subscribe
  lazy val path = paths.head.ensuring(paths.size == 1)
  def split = paths.map(SubscribeRequest(rid, _))

  /**
   * Outputs only the first three paths for compact logging.
   */
  override def toString = {
    val more = Option(paths.size - 3) filter (_ > 0) map (n => s"...$n more") getOrElse ""
    s"SubscribeRequest($rid,List(${paths.take(3).mkString(",")}$more))"
  }
}
object SubscribeRequest {
  def apply(rid: Int, paths: SubscriptionPath*): SubscribeRequest = apply(rid, paths.toList)
}

/**
 * UNSUBSCRIBE request.
 */
case class UnsubscribeRequest(rid: Int, sids: List[Int]) extends DSARequest {
  val method = Unsubscribe
  lazy val sid = sids.head.ensuring(sids.size == 1)
  def split = sids.map(UnsubscribeRequest(rid, _))

  /**
   * Outputs only the first ten sids for compact logging.
   */
  override def toString = {
    val more = Option(sids.size - 10) filter (_ > 0) map (n => s"...$n more") getOrElse ""
    s"UnsubscribeRequest($rid,List(${sids.take(10).mkString(",")}$more))"
  }
}
object UnsubscribeRequest {
  def apply(rid: Int, sids: Int*): UnsubscribeRequest = apply(rid, sids.toList)
}

/**
 * CLOSE request.
 */
case class CloseRequest(rid: Int) extends DSARequest {
  val method = Close
}