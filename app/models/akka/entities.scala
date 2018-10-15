package models.akka

import javax.annotation.concurrent.NotThreadSafe

/**
 * Encapsulates DSLink information for WebSocket connection.
 */
case class ConnectionInfo(dsId: String, linkName: String, isRequester: Boolean, isResponder: Boolean,
                          linkData: Option[String] = None, version: String = "",
                          formats: List[String] = Nil, compression: Boolean = false,
                          linkAddress: String = "", brokerAddress: String = ""
                          , resultFormat: String = models.rpc.DSAMessageSerrializationFormat.MSGJSON
                          , tempKey: String = "", sharedSecret: Array[Byte] = Array.emptyByteArray, salt: String = ""
                         )
{
  val mode = (isRequester, isResponder) match {
    case (true, true)  => DSLinkMode.Dual
    case (true, false) => DSLinkMode.Requester
    case (false, true) => DSLinkMode.Responder
    case _             => throw new IllegalArgumentException("DSLink must be Requester, Responder or Dual")
  }
}

/**
  * Counter for rid/sid/msg. According to [[https://github.com/IOT-DSA/docs/wiki/Node-API]]
  * the maximum value of counter equals to Int.MaxValue and must be reset to 1 in case of reaching the limit
  */
@NotThreadSafe
class IntCounter(private val init: Int = 0) {
  private var value = init

  @inline def get = value

  @inline def inc = {
    val result = value
    value = if (value == Int.MaxValue) init else value + 1
    result
  }

  @inline def inc(count: Int): Seq[Int] = {
    val start = value
    if (Int.MaxValue - count >= start) {
      value += count
      start until value
    } else {
      // Avoiding overflow
      val tmpLong: Long = init.toLong + start + count - Int.MaxValue - 1
      value = tmpLong.toInt
      (start to Int.MaxValue) ++ (init until value)
    }
  }
}

/**
 * DSA Link mode.
 */
object DSLinkMode extends Enumeration {
  type DSLinkMode = Value
  val Responder, Requester, Dual = Value
}