package models

import scala.concurrent.Future

/**
 * Metric types and helper functions.
 */
package object metrics {
  type ListResult[T] = Future[List[T]]
}