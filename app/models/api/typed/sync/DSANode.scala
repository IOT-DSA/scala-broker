package models.api.typed.sync

import models.rpc.DSAValue.{ DSAMap, DSAVal }
import models.api.typed.InitState

/**
 * The synchronous version of DSANode functionality.
 */
trait DSANode {
  def name: String
  def parent: Option[DSANode]

  def displayName: Option[String]
  def displayName_=(s: Option[String]): Unit

  def value: DSAVal
  def value_=(v: DSAVal): Unit

  def attributes: DSAMap
  def attributes_=(attrs: DSAMap): Unit
  def addAttributes(cfg: (String, DSAVal)*): Unit
  def removeAttribute(name: String): Unit
  def clearAttributes(): Unit

  def children: Map[String, DSANode]
  def addChild(name: String, state: InitState): DSANode
  def removeChild(name: String): Unit
  def removeChildren(): Unit
}