package models.akka.cluster

import akka.actor.{Identify, PoisonPill, Props, actorRef2Scala}
import akka.pattern.pipe
import akka.routing.Routee
import akka.stream.scaladsl.Source
import models.akka.Messages._
import models.akka.{DSLinkFolderActor, IsNode, RichRoutee, rows}
import models.rpc.DSAValue.{ArrayValue, DSAVal, StringValue, array, obj}

import scala.concurrent.Future

/**
  * Actor for clustered DSA link folder node, such as `/upstream` or `/downstream`.
  */
class ClusteredDSLinkFolderActor(linkPath: String, linkProxy: (String) => Routee, extraConfigs: (String, DSAVal)*)
  extends DSLinkFolderActor(linkPath) with ClusteredActor {

  import context.dispatcher

  /**
    * Handles incoming messages.
    */
  def receive = responderBehavior orElse mgmtHandler

  /**
    * Handler for messages coming from peer nodes.
    */
  private val peerMsgHandler: Receive = {

    case RegisterDSLink(name, mode, connected) => notifyOnRegister(name)

    case GetDSLinkNames => sender ! links.keys.toList

    case UnregisterDSLink(name) => notifyOnRemove(name)

    case DSLinkStateChanged(name, mode, connected) => changeLinkState(name, mode, connected, false)

    case GetDSLinkStats => sender ! buildDSLinkNodeStats

    case FindDSLinks(regex, limit, offset) => sender ! findDSLinks(regex, limit, offset)

    case RemoveDisconnectedDSLinks => removeDisconnectedDSLinks
  }

  /**
    * Handles control messages.
    */
  val mgmtHandler: Receive = {

    case PeerMessage(msg) => peerMsgHandler(msg)

    case GetOrCreateDSLink(name) =>
      log.info("{}: requested DSLink '{}'", ownId, name)
      sender ! getOrCreateDSLink(name)

    case msg@RegisterDSLink(name, mode, connected) =>
      links += (name -> LinkState(mode, connected))
      tellPeers(PeerMessage(msg))
      log.info("{}: registered DSLink '{}'", ownId, name)

    case GetDSLinkNames =>
      val nodeLinks = askPeers[Iterable[String]](PeerMessage(GetDSLinkNames)) map (_ flatMap (_._2))
      nodeLinks pipeTo sender

    case RemoveDSLink(name) =>
      removeDSLinks(name)
      log.info("{}: rrdered to remove DSLink '{}'", ownId, name)

    case msg@UnregisterDSLink(name) =>
      links -= name
      tellPeers(PeerMessage(msg))
      log.info("{}: removed DSLink '{}'", ownId, name)

    case evt@DSLinkStateChanged(name, mode, connected) =>
      log.info("{}: DSLink state changed: '{}'", ownId, evt)
      tellPeers(PeerMessage(evt))

    case GetDSLinkStats =>
      val nodeStats = askPeers[DSLinkNodeStats](PeerMessage(GetDSLinkStats))
      nodeStats map DSLinkStats pipeTo sender

    case FindDSLinks(regex, limit, offset) =>
      val nodeLinks = askPeers[Seq[String]](PeerMessage(FindDSLinks(regex, limit, offset)))
      nodeLinks pipeTo sender

    case RemoveDisconnectedDSLinks =>
      removeDisconnectedDSLinks
      tellPeers(PeerMessage(RemoveDisconnectedDSLinks))
  }

  /**
    * Creates/accesses a new DSLink actor and returns a [[Routee]] instance for it.
    */
  protected def getOrCreateDSLink(name: String): Routee = {
    val routee = linkProxy(name)
    routee ! Identify
    routee
  }

  /**
    * Terminates the specified DSLink actors.
    */
  protected def removeDSLinks(names: String*) = names map linkProxy foreach (_ ! PoisonPill)

  /**
    * Creates a stream of values in response to LIST request by querying peer nodes
    * and concatenating their lists with its own.
    */
  protected def listNodes: Source[ArrayValue, _] = {

    def toImmutable[A](elements: Iterable[A]) = new scala.collection.immutable.Iterable[A] {
      override def iterator: Iterator[A] = elements.toIterator
    }

    val configs = rows(IsNode) ++ rows(extraConfigs: _*)

    val otherLinks = queryPeers[Iterable[String]](PeerMessage(GetDSLinkNames), false)

    val children = otherLinks.map(_._2) + Future.successful(links.keys)

    val sources = children.map(names => Source.fromFuture(names).mapConcat(toImmutable))

    Source(configs) ++ sources.foldLeft(Source.empty[String])(_ ++ _).map(name => array(name, obj(IsNode)))
  }
}

/**
  * Factory for [[ClusteredDSLinkFolderActor]] instances.
  */
object ClusteredDSLinkFolderActor {

  /**
    * Creates a new props for [[ClusteredDSLinkFolderActor]].
    */
  def props(linkPath: String, linkProxy: (String) => Routee, extraConfigs: (String, DSAVal)*) =
    Props(new ClusteredDSLinkFolderActor(linkPath, linkProxy, extraConfigs: _*))
}