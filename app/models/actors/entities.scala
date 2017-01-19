package models.actors

import models.{ DSARequest, DSAResponse }

/**
 * Encapsulates DSLink information for WebSocket connection.
 */
case class ConnectionInfo(dsId: String, isRequester: Boolean, isResponder: Boolean, linkPath: String)

/**
 * Envelope for internal request routing.
 */
case class RequestEnvelope(request: DSARequest)

/**
 * Envelope for internal response routing.
 */
case class ResponseEnvelope(response: DSAResponse)

/**
 * Similar to [[java.util.concurrent.atomic.AtomicInteger]], but not thread safe,
 * optimized for single threaded execution by an actor.
 */
class IntCounter(init: Int = 0) {
  private var value = init

  @inline def get = value
  @inline def inc = {
    val result = value
    value += 1
    result
  }
}

/**
 * Keeps track of target IDs and manages the lookups between source and target ID.
 */
class IdLookup(next: Int = 1) {
  private var nextTargetId = new IntCounter(next)
  private val sourceIdLookup = collection.mutable.Map.empty[Int, Int]
  private val targetIdLookup = collection.mutable.Map.empty[Int, Int]

  def createTargetId(sourceId: Int) = {
    val nextId = nextTargetId.inc
    sourceIdLookup(nextId) = sourceId
    targetIdLookup(sourceId) = nextId
    nextId
  }
  
  def targetId(sourceId: Int) = targetIdLookup(sourceId)
  
  def sourceId(targetId: Int) = sourceIdLookup(targetId)
}