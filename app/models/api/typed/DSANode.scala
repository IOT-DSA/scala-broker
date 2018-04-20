package models.api.typed

import scala.concurrent.Future

import models.rpc.DSAValue.{ DSAMap, DSAVal }

/**
 * The asynchronous version of DSANode functionality.
 */
trait DSANodeAsync {
  def name: Future[String]
  def parent: Future[Option[DSANodeAsync]]

  def displayName: Future[String]
  def displayName_=(s: String): Unit

  def value: Future[DSAVal]
  def value_=(v: DSAVal): Unit

  def attributes: Future[DSAMap]
  def attributes_=(attrs: DSAMap): Unit
  def addAttributes(cfg: (String, DSAVal)*): Unit
  def removeAttribute(name: String): Unit

  def children: Future[Map[String, DSANodeAsync]]
  def addChild(name: String): Future[DSANodeAsync]
  def removeChild(name: String): Unit
}

/**
 * The synchronous version of DSANode functionality.
 */
trait DSANode {
  def name: String
  def parent: Option[DSANode]

  def displayName: String
  def displayName_=(s: String): Unit

  def value: DSAVal
  def value_=(v: DSAVal): Unit

  def attributes: DSAMap
  def attributes_=(attrs: DSAMap): Unit
  def addAttributes(cfg: (String, DSAVal)*): Unit
  def removeAttribute(name: String): Unit

  def children: Map[String, DSANode]
  def addChild(name: String): DSANode
  def removeChild(name: String): Unit
}