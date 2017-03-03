package models.api

import DSAValueType.DSAValueType
import models.rpc.DSAValue.DSAMap

/**
 * Action execution context.
 */
case class ActionContext(node: DSANode, args: DSAMap)

/**
 * DSA Action.
 */
case class DSAAction(handler: ActionContext => Unit, params: (String, DSAValueType)*)