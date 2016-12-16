package org.dsa.iot.broker

import org.json4s.JValue

/**
 * Converts an entity into JSON.
 */
trait JsonExport {
  def toJson: JValue
}

/**
 * Restores an entity from JSON.
 */
trait JsonFactory[T] {
  def fromJson: PartialFunction[JValue, T]
}