package models.metrics

import org.joda.time.DateTime

import models.akka.DSLinkMode.DSLinkMode
import models.rpc.{ RequestMessage, ResponseMessage, DSARequest, DSAResponse }

/**
 * An empty stub for [[MetricLogger]], used when metric collection is turned off.
 */
class NullMetricLogger extends MetricLogger {

  def logHandshake(ts: DateTime,
                   linkId: String, linkName: String, linkAddress: String, mode: DSLinkMode,
                   version: String, compression: Boolean, brokerAddress: String) = {}

  def logWebSocketSession(startTime: DateTime, endTime: DateTime, linkName: String,
                          linkAddress: String, mode: DSLinkMode, brokerAddress: String) = {}

  def logRequestMessage(ts: DateTime, linkName: String, linkAddress: String, message: RequestMessage) = {}

  def logResponseMessage(ts: DateTime, linkName: String, linkAddress: String, message: ResponseMessage) = {}

  def logRequests(ts: DateTime, srcLinkName: String, srcLinkAddress: String, tgtLinkName: String,
                  requests: DSARequest*) = {}

  def logResponses(ts: DateTime, linkName: String, linkAddress: String, responses: DSAResponse*) = {}
}