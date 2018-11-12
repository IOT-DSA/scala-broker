package models.akka

import java.util.regex.Pattern

import akka.persistence.PersistentActor
import akka.actor.ActorLogging
import akka.routing.Routee
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import models.akka.DSLinkMode.DSLinkMode
import models.akka.Messages.{DSLinkNodeStats, LinkState}
import models.akka.responder.SimpleResponderBehavior
import models.rpc.{ CloseRequest, DSARequest, DSAResponse, ListRequest, ResponseMessage }
import models.{RequestEnvelope, Settings}

/**
 * Base actor for DSA "link folder" nodes, such as `/downstream` or `/upstream`.
 *
 * When started, it verifies its own location against the `linkPath` parameter. For example,
 * if linkPath is set to "/downstream", the actor path needs to be "/user/downstream".
 */
abstract class DSLinkFolderActor(val linkPath: String) extends PersistentActor with ActorLogging with RouteeNavigator with SimpleResponderBehavior {
  import models.rpc.DSAValue._
  import models.rpc.StreamState._
  
  checkPath(linkPath)

  protected def ownId: String = "[" + linkPath + "]"

  protected var links = Map.empty[String, LinkState]

  protected var listRid: Option[Int] = None

  override def preStart = log.info(s"$ownId actor created")

  override def postStop = log.info(s"$ownId actor stopped")

  /**
    * Returns a [[Routee]] that can be used for sending messages to a specific downlink.
    */
  override def getDownlinkRoutee(dsaName: String): Routee = ???

  /**
    * Returns a [[Routee]] that can be used for sending messages to a specific uplink.
    */
  override def getUplinkRoutee(dsaName: String): Routee = ???


  override def updateRoutee(routee: Routee): Routee = routee

  val dslinkFolderRecover: Receive = {
    case event: DSLinkCreated =>
      log.debug("{}: recovering with event {}", ownId, event)
      getOrCreateDSLink(event.name)
    case event: DSLinkRemoved =>
      log.debug("{}: recovering with event {}", ownId, event)
      removeDSLinks(event.names: _*)
    case event: DSLinkRegistered =>
      log.debug("{}: recovering with event {}", ownId, event)
      links += (event.name -> LinkState(event.mode, event.connected))
    case event: DSLinkUnregistered =>
      log.debug("{}: recovering with event {}", ownId, event)
      links -= event.name
    case event: ListRidUpdated =>
      log.debug("{}: recovering with event {}", ownId, event)
      listRid = event.listRid
    case offeredSnapshot: DSLinkFolderState =>
      log.debug("{}: recovering with snapshot {}", ownId, offeredSnapshot)
      links = offeredSnapshot.links
      listRid = offeredSnapshot.listRid
  }

  implicit val mat = ActorMaterializer()

  /**
   * Terminates the actor system if the actor's path does not match `/user/<path>`.
   */
  protected def checkPath(path: String) = if (self.path != self.path.root / "user" / path.split("/")) {
    val msg = s"${getClass.getSimpleName} should be created under [$path]"
    log.error(new IllegalStateException(msg), msg + ", not [" + self.path + "]")
    context.system.terminate
  }

  /**
   * Removes DSLinks actors that do not have a connected endpoint.
   */
  protected def removeDisconnectedDSLinks = {
    val disconnected = links.filterNot(_._2.connected).keys.toSeq
    persist(DSLinkRemoved(disconnected: _*)) { event =>
      log.debug("{}: persisting {}", ownId, event)
      removeDSLinks(event.names: _*)
      saveSnapshot(DSLinkFolderState(links, listRid))
      log.info("{}: removed {} disconnected DSLinks", ownId, event.names.size)
    }
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
   * Processes a DSA payload and forwards the results to [[models.akka.responder.ResponderBehavior]].
   */
  protected def sendToEndpoint(msg: Any): Unit = msg match {
    case RequestEnvelope(requests) => requests foreach handleRequest
    case _                         => log.warning("Unknown message received: {}", msg)
  }

  /**
   * Generates response for LIST request.
   */
  protected def listNodes: Source[ArrayValue, _]

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
  protected def handleRequest(request: DSARequest): Unit = request match {
    case ListRequest(rid, "/") =>
      persist(ListRidUpdated(Some(rid))) { event =>
        log.debug("{}: persisting {}", ownId, event)
        listRid = event.listRid
        saveSnapshot(DSLinkFolderState(links, listRid))
        val messages = listNodes grouped Settings.ChildrenPerListResponse map { rows =>
          val response = DSAResponse(rid = event.listRid.get, stream = Some(Open), updates = Some(rows.toList))
          ResponseMessage(-1, None, List(response))
        }
        messages.runForeach(self.forward)
      }
    case CloseRequest(_) =>
      persist(ListRidUpdated(None)) { event =>
        log.debug("{}: persisting {}", ownId, event)
        listRid = event.listRid
        saveSnapshot(DSLinkFolderState(links, listRid))
      }
    case _ => log.error(s"Invalid request - $request")
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
        persist(DSLinkRegistered(name, mode, connected)) { event =>
          log.debug("{}: persisting {}", ownId, event)
          links += (event.name -> LinkState(event.mode, event.connected))
          saveSnapshot(DSLinkFolderState(links, listRid))
          log.info("{}: DSLink '{}' state changed to: mode={}, connected={}", ownId, event.name, event.mode, event.connected)
        }
      case None if warnIfNotFound =>
        log.warning("{}: DSLink '{}' is not registered, ignoring state change", ownId, name)
      case _ =>
    }
  }
}