package models.rpc

import akka.stream.scaladsl.Flow
import akka.util.ByteString
import play.api.http.websocket._
import play.api.libs.json.JsValue
import play.api.libs.streams.AkkaStreams
import play.api.mvc.WebSocket.MessageFlowTransformer

import scala.util.control.NonFatal
import org.velvia.msgpack
import org.velvia.msgpack.PlayJsonCodecs.JsValueCodec
import play.api.libs.json.{Reads, Writes, Json, JsError}

object MsgpackTransformer {

  implicit val jsonFlowTransformer: MessageFlowTransformer[JsValue, JsValue] = {
    def closeOnException[T](block: => T) = try {
      Left(block)
    } catch {
      case NonFatal(e) => Right(
        CloseMessage(Some(CloseCodes.Unacceptable),
                     "Unable to parse json message")
        )
    }

    new MessageFlowTransformer[JsValue, JsValue] {
      def transform(flow: Flow[JsValue, JsValue, _]) = {
        AkkaStreams.bypassWith[Message, JsValue, Message](Flow[Message].collect {
          case BinaryMessage(data) =>
            closeOnException(
              msgpack.unpack(data.toArray)
            )
        })(flow map { json =>
                      BinaryMessage(ByteString(msgpack.pack(json)))
                    }
          )
      }
    }
  }

  /**
    * Converts messages to/from a JSON high level object.
    *
    * If the input messages fail to be parsed, the WebSocket will be closed with an 1003 close code and the parse error
    * serialised to JSON.
    */
  def msaMessageFlowTransformer[In: Reads, Out: Writes]: MessageFlowTransformer[In, Out] = {
    jsonFlowTransformer.map(
      json => Json.fromJson[In](json).fold(
        { errors =>
          throw WebSocketCloseException(CloseMessage(Some(CloseCodes.Unacceptable)
                                                     , Json.stringify(JsError.toJson(errors)))
          )
        }
        , identity
      )
      , out => Json.toJson(out)
    )
  }
}