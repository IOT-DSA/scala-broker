package models

import scala.concurrent.Future

/**
 * Metric types and helper functions.
 */
package object metrics {

  /**
   * A single entity return type.
   */
  type Result[T] = Future[T]

  /**
   * An optional entity return type.
   */
  type OptResult[T] = Future[Option[T]]

  /**
   * Entity list return type.
   */
  type ListResult[T] = Future[List[T]]
}