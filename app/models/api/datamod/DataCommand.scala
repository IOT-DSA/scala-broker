package models.api.datamod

import models.rpc.DSAValue.DSAVal

trait DataCommand {
  val path: String
}

case class SetValue(path: String, value: DSAVal) extends DataCommand

case class AddChild(path: String, name: String) extends DataCommand

case class RemoveChild(path: String, name: String) extends DataCommand

case class AddAttributes(path: String, attributes: (String, DSAVal)*) extends DataCommand

case class RemoveAttribute(path: String, name: String) extends DataCommand

