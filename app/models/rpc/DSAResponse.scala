package models.rpc

import DSAValue._

/**
 * Supported Stream states.
 */
object StreamState extends Enumeration {
  type StreamState = Value

  val Initialize = Value("initialize")
  val Open = Value("open")
  val Closed = Value("closed")
}
import StreamState._

/**
 * Describes a DSA error.
 */
case class DSAError(msg: Option[String] = None, errType: Option[String] = None, phase: Option[String] = None,
                    path: Option[String] = None, detail: Option[String] = None)

/**
 * Encapsulates update column information.
 */
case class ColumnInfo(colName: String, colType: String)

/**
 * DSA Response.
 */
case class DSAResponse(rid: Int, stream: Option[StreamState] = None, updates: Option[List[DSAVal]] = None,
                       columns: Option[List[ColumnInfo]] = None, error: Option[DSAError] = None) {

  /**
   * Outputs only the first three updates for compact logging.
   */
  override def toString = {
    val more = updates map (_.size - 3) filter (_ > 0) map (n => s"...$n more") getOrElse ""
    val rows = updates map (_.take(3).mkString(",")) map (str => s"List($str$more)")
    s"DSAResponse($rid,$stream,$rows,$columns,$error)"
  }
}