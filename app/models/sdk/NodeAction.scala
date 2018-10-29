package models.sdk

import akka.stream.scaladsl.Source
import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue._

import scala.concurrent.Future

/**
  * Action parameter description.
  *
  * @param name
  * @param dsaType
  * @param editor
  * @param writable
  * @param output
  */
case class Param(name: String, dsaType: DSAValueType,
                 editor: Option[String] = None,
                 writable: Option[String] = None,
                 output: Boolean = false) {

  def withEditor(editor: String) = copy(editor = Some(editor))

  def writableAs(writable: String) = copy(writable = Some(writable))

  def asOutput() = copy(output = true)

  def toMapValue = {
    val items = obj("name" -> name, "type" -> dsaType).value ++
      editor.map(e => "editor" -> (e: DSAVal)) ++
      writable.map(w => "writable" -> (w: DSAVal)) ++
      (if (output) List("output" -> BooleanValue(true)) else Nil)

    MapValue(items)
  }
}

/**
  * Action execution context.
  */
case class ActionContext(actionNode: NodeRef, args: DSAMap) {

  def as[T](name: String)(implicit cnv: DSAVal => T): T = args(name)

  def get[T](name: String)(implicit cnv: DSAVal => T): Option[T] = args.get(name).map(cnv)
}

/**
  * DSAVal conversion functions.
  */
object ActionContext {

  implicit def dsa2boolean(arg: DSAVal) = arg match {
    case x: BooleanValue => x.value
    case _               => throw new IllegalArgumentException("Wrong argument type, must be NumericValue")
  }

  implicit def dsa2binary(arg: DSAVal) = arg match {
    case x: BinaryValue => x.value
    case _              => throw new IllegalArgumentException("Wrong argument type, must be BinaryValue")
  }

  implicit def dsa2string(arg: DSAVal) = arg match {
    case x: StringValue => x.value
    case _              => throw new IllegalArgumentException("Wrong argument type, must be StringValue")
  }

  implicit def dsa2number(arg: DSAVal) = arg match {
    case x: NumericValue => x.value
    case _               => throw new IllegalArgumentException("Wrong argument type, must be NumericValue")
  }

  implicit def dsa2int(arg: DSAVal)(implicit cnv: DSAVal => BigDecimal) = cnv(arg).toInt

  implicit def dsa2long(arg: DSAVal)(implicit cnv: DSAVal => BigDecimal) = cnv(arg).toLong

  implicit def dsa2double(arg: DSAVal)(implicit cnv: DSAVal => BigDecimal) = cnv(arg).toDouble

  implicit def dsa2array(arg: DSAVal) = arg match {
    case x: ArrayValue => x.value
    case _             => throw new IllegalArgumentException("Wrong argument type, must be ArrayValue")
  }

  implicit def dsa2map(arg: DSAVal) = arg match {
    case x: MapValue => x.value
    case _           => throw new IllegalArgumentException("Wrong argument type, must be MapValue")
  }
}

/**
  * Actions result that includes the initially returned value and an optional stream.
  *
  * @param value
  * @param stream
  */
case class ActionResult(value: DSAVal, stream: Option[Source[DSAVal, _]] = None) {
  val closed = stream.isEmpty
}

/**
  * Action handler.
  */
trait ActionHandler extends (ActionContext => Future[ActionResult]) with Serializable

/**
  * DSA Action.
  */
case class NodeAction(handler: ActionHandler, params: Param*)