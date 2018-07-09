package infrastructure.tester

import org.dsa.iot.dslink.node.value.{Value}
import org.dsa.iot.dslink.util.json.{JsonArray, JsonObject}

/**
  * every time i create Utils tool or package i think
  * "i shouldn't do ths, but ... "
  */
object TestUtils {

  def toValue(newValue:Any, ms:Option[Long] = None):Value = {
    val value = newValue match {
      case data: Number => new Value(data)
      case data: String => new Value(data)
      case data: Boolean => new Value(data)
      case data: JsonObject => new Value(data)
      case data: JsonArray => new Value(data)
      case _ => throw new RuntimeException("unsupported value type")
    }
    ms.foreach{value.setTime(_)}
    value
  }

}
