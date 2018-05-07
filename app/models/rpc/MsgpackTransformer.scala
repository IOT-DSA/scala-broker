package models.rpc

import akka.stream.scaladsl.Flow
import play.api.http.websocket._
import play.api.libs.json._
import play.api.libs.streams.AkkaStreams
import play.api.mvc.WebSocket.MessageFlowTransformer

import scala.util.control.NonFatal

object MessageFlowTransformer2 {

  implicit val msgpackMessageFlowTransformer: MessageFlowTransformer[JsValue, JsValue] = {
    def closeOnException[T](block: => T) = try {
      Left(block)
    } catch {
      case NonFatal(e) => Right(CloseMessage(
        Some(CloseCodes.Unacceptable),
        "Unable to parse json message"))
    }

    new MessageFlowTransformer[JsValue, JsValue] {
      def transform(flow: Flow[JsValue, JsValue, _]) = {
        AkkaStreams.bypassWith[Message, JsValue, Message](Flow[Message].collect {
          case BinaryMessage(data) => closeOnException(Json.parse(data.iterator.asInputStream))
          case TextMessage(text) => closeOnException(Json.parse(text))
        })(flow map { json => TextMessage(Json.stringify(json)) })
      }
    }
  }

  /**
    * Converts messages to/from a JSON high level object.
    *
    * If the input messages fail to be parsed, the WebSocket will be closed with an 1003 close code and the parse error
    * serialised to JSON.
    */
  def jsonMessageFlowTransformer[In: Reads, Out: Writes]: MessageFlowTransformer[In, Out] = {
    msgpackMessageFlowTransformer.map(json => Json.fromJson[In](json).fold({ errors =>
      throw WebSocketCloseException(CloseMessage(Some(CloseCodes.Unacceptable), Json.stringify(JsError.toJson(errors))))
    }, identity), out => Json.toJson(out))
  }

}