package models.api.typed.async

import scala.concurrent.Future

import models.rpc.DSAValue.{ DSAMap, DSAVal }
import models.api.typed.InitState

/**
 * The asynchronous version of DSANode functionality.
 */
trait DSANodeAsync {
  def name: Future[String]
  def parent: Future[Option[DSANodeAsync]]

  def displayName: Future[Option[String]]
  def displayName_=(s: Option[String]): Unit

  def value: Future[DSAVal]
  def value_=(v: DSAVal): Unit

  def attributes: Future[DSAMap]
  def attributes_=(attrs: DSAMap): Unit
  def addAttributes(cfg: (String, DSAVal)*): Unit
  def removeAttribute(name: String): Unit
  def clearAttributes(): Unit

  def children: Future[Map[String, DSANodeAsync]]
  def addChild(name: String, state: InitState): Future[DSANodeAsync]
  def removeChild(name: String): Unit
  def removeChildren(): Unit
}