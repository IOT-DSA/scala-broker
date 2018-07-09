package models.api

import models.rpc.DSAValue.{DSAMap}

/**
 * Action execution context.
 */
case class ActionContext(node: DSANode, args: DSAMap)

/**
 * DSA Action.
 */
case class DSAAction(handler: ActionContext => Any, params: DSAMap*)
