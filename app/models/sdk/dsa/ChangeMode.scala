package models.sdk.dsa

import models.rpc.DSAValue

/**
  * Change modes supported by LIST command.
  */
object ChangeMode extends Enumeration {
  type ChangeMode = Value

  val UPDATE = Value("update")
  val REMOVE = Value("remove")

  val DEFAULT = UPDATE

  implicit def changeModeToValue(x: ChangeMode) = DSAValue.StringValue(x.toString)
}
