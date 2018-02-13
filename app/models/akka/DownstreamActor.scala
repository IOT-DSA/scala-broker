package models.akka

import java.util.regex.Pattern

import akka.actor.{ Actor, ActorLogging }
import akka.routing.Routee
import models.{ RequestEnvelope, Settings }
import models.akka.DSLinkMode.DSLinkMode
import models.akka.Messages.{ DSLinkNodeStats, LinkState }
import models.akka.responder.SimpleResponderBehavior
import models.rpc.{ CloseRequest, DSARequest, DSAResponse, ListRequest, ResponseMessage }

/**
 * Base actor for DSA `/downstream` node.
 * To ensure the correct routing, it needs to be created by the actor system under `downstream`
 * name, so its full path is `/user/downstream`:
 * <pre>
 * actorSystem.actorOf(..., "downstream")
 * </pre>
 */
abstract class DownstreamActor extends Actor with ActorLogging with SimpleResponderBehavior {
  import models.rpc.DSAValue._
  import models.rpc.StreamState._

  checkPath(Settings.Nodes.Downstream)

  protected val linkPath: String = Settings.Paths.Downstream

  protected val ownId: String = "[" + linkPath + "]"

  protected var links = Map.empty[String, LinkState]

  protected var listRid: Option[Int] = None

  override def preStart = log.info(s"$ownId actor created")

  override def postStop = log.info(s"$ownId actor stopped")

  /**
   * Terminates the actor system if the actor's path does not match `/user/<path>`.
   */
  protected def checkPath(path: String) = if (self.path != self.path.root / "user" / path) {
    val msg = s"${getClass.getSimpleName} should be created under [$path]"
    log.error(new IllegalStateException(msg), msg + ", not [" + self.path + "]")
    context.system.terminate
  }

  /**
   * Removes DSLinks actors that do not have a connected endpoint.
   */
  protected def removeDisconnectedDSLinks = {
    val disconnected = links.filterNot(_._2.connected).keys.toSeq
    removeDSLinks(disconnected: _*)
    log.info("{}: removed {} disconnected DSLinks", ownId, disconnected.size)
  }

  /**
   * Searches DSLinks by name pattern.
   */
  protected def findDSLinks(regex: String, limit: Int, offset: Int) = {
    log.debug("{}: searching for dslinks: pattern={}, limit={}, offset={}", ownId, regex, limit, offset)
    val pattern = Pattern.compile(regex)
    val filtered = links.keys.filter(pattern.matcher(_).matches).toList.sorted
    filtered.drop(offset).take(limit)
  }

  /**
   * Returns the current DSLink node stats.
   */
  protected def buildDSLinkNodeStats = {
    val requestersOn = links.count(_._2 == LinkState(DSLinkMode.Requester, true))
    val requestersOff = links.count(_._2 == LinkState(DSLinkMode.Requester, false))
    val respondersOn = links.count(_._2 == LinkState(DSLinkMode.Responder, true))
    val respondersOff = links.count(_._2 == LinkState(DSLinkMode.Responder, false))
    val dualsOn = links.count(_._2 == LinkState(DSLinkMode.Dual, true))
    val dualsOff = links.count(_._2 == LinkState(DSLinkMode.Dual, false))
    DSLinkNodeStats(
      self.path.address,
      requestersOn, requestersOff,
      respondersOn, respondersOff,
      dualsOn, dualsOff)
  }

  /**
   * Processes a DSA payload and forwards the results to [[ResponderBehavior]].
   */
  protected def sendToEndpoint(msg: Any): Unit = msg match {
    case RequestEnvelope(requests) => requests flatMap processRequest foreach responderBehavior
    case _                         => log.warning("Unknown message received: {}", msg)
  }

  /**
   * Generates response for LIST request.
   */
  protected def listNodes: Iterable[ArrayValue]

  /**
   * Creates/accesses a new DSLink actor and emits an update, if there is an active LIST request.
   */
  protected def getOrCreateDSLink(name: String): Routee

  /**
   * Orders the removal of the specified DSLink actors.
   */
  protected def removeDSLinks(names: String*): Unit

  /**
   * Processes an incoming request and produces a list of response envelopes, if any.
   */
  protected def processRequest(request: DSARequest): TraversableOnce[ResponseMessage] = request match {
    case ListRequest(rid, "/") =>
      listRid = Some(rid)
      listNodes grouped Settings.ChildrenPerListResponse map { rows =>
        val response = DSAResponse(rid = rid, stream = Some(Open), updates = Some(rows.toList))
        ResponseMessage(-1, None, List(response))
      }
    case CloseRequest(_) =>
      listRid = None
      Nil
    case _ =>
      log.error(s"Invalid request - $request")
      Nil
  }

  /**
   * Generates and sends response messages to notify the listeners about the registered dslink.
   */
  protected def notifyOnRegister(name: String): Unit = listRid foreach { rid =>
    val updates = rows(name -> obj(IsNode))
    self ! ResponseMessage(-1, None, DSAResponse(rid, Some(Open), Some(updates)) :: Nil)
  }

  /**
   * Generates and sends response messages to notify the listeners about the removed dslinks.
   */
  protected def notifyOnRemove(names: String*) = listRid foreach { rid =>
    val updates = names map (name => obj("name" -> name, "change" -> "remove"))
    self ! ResponseMessage(-1, None, DSAResponse(rid, Some(Open), Some(updates.toList)) :: Nil)
  }

  /**
   * Changes the recorded link state.
   */
  protected def changeLinkState(name: String, mode: DSLinkMode, connected: Boolean, warnIfNotFound: Boolean) = {
    links.get(name) match {
      case Some(_) =>
        links += (name -> LinkState(mode, connected))
        log.info("{}: DSLink '{}' state changed to: mode={}, connected={}", ownId, name, mode, connected)
      case None if warnIfNotFound =>
        log.warning("{}: DSLink '{}' is not registered, ignoring state change", ownId, name)
      case _ =>
    }
  }
}