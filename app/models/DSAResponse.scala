package models

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
case class DSAResponse(rid: Int, stream: StreamState, updates: Option[List[DSAVal]] = None,
                       columns: Option[List[ColumnInfo]] = None, error: Option[DSAError] = None)