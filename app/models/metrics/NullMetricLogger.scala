package models.metrics

import models.akka.DSLinkMode.DSLinkMode
import org.joda.time.DateTime

/**
 * An empty stub for [[MetricLogger]], used when metric collection is turned off.
 */
class NullMetricLogger extends MetricLogger {

  def logHandshake(ts: DateTime,
                   linkId: String, linkName: String, linkAddress: String, mode: DSLinkMode,
                   version: String, compression: Boolean, brokerAddress: String) = {}
}