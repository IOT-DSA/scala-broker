package models.akka

import javax.annotation.concurrent.NotThreadSafe
import models.akka.IntCounter.IntCounterState

/**
  * Encapsulates DSLink information for WebSocket connection.
  */
case class ConnectionInfo(dsId: String, linkName: String, isRequester: Boolean, isResponder: Boolean,
                          linkData: Option[String] = None, version: String = "",
                          clientFormats: List[String] = Nil, compression: Boolean = false,
                          linkAddress: String = "", brokerAddress: String = "",
                          format: String = models.rpc.DSAMessageSerrializationFormat.MSGJSON,
                          tempKey: String = "", sharedSecret: Array[Byte] = Array.emptyByteArray, salt: String = "",
                          tokenHash: Option[String] = None
                         ) {
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
class IntCounter(val state: IntCounterState) {

  @inline def get = state.value

  @inline def inc = {
    val result = state.value
    state.value = if (state.value == Int.MaxValue) state.init else state.value + 1
    result
  }

  @inline def inc(count: Int): Seq[Int] = {
    val start = state.value
    if (Int.MaxValue - count >= start) {
      state.value += count
      start until state.value
    } else {
      // Avoiding overflow
      state.value = count - 1 - (Int.MaxValue - start) + state.init
      (start to Int.MaxValue) ++ (state.init until state.value)
    }
  }
}

//Fabric for [[IntCounter]]
object IntCounter {
  case class IntCounterState(init: Int, var value: Int)
  def apply(init: Int = 0) = new IntCounter(IntCounterState(init, init))
  def apply(state: IntCounterState) = new IntCounter(state)
}

/**
  * DSA Link mode.
  */
object DSLinkMode extends Enumeration {
  type DSLinkMode = Value
  val Responder, Requester, Dual = Value
}