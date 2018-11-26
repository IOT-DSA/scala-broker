package models.sdk.node

import akka.stream.scaladsl.Source
import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue._
import models.sdk.NodeRef

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