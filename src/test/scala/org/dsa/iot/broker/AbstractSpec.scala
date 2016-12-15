package org.dsa.iot.broker

import org.scalatest.{ BeforeAndAfterAll, Matchers, Suite, WordSpecLike }
import org.scalatest.prop.GeneratorDrivenPropertyChecks

/**
 * Base trait for test specifications.
 */
class AbstractSpec extends Suite
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with GeneratorDrivenPropertyChecks {
}