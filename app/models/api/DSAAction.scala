package models.api

import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue._

/**
  * Action execution context.
  */
case class ActionContext(node: DSANode, args: DSAMap)

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
  * DSA Action.
  */
case class DSAAction(handler: ActionContext => Any, params: Param*)