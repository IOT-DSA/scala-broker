package models.akka.local

import akka.util.Timeout
import akka.actor._
import akka.pattern.ask
import models.Settings
import scala.reflect.ClassTag
import models.akka._
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/**
 * Uses Akka Actor Selection to communicate with DSLinks.
 */
class LocalDSLinkManager(implicit val system: ActorSystem) extends DSLinkManager {
  import Settings._
  import Messages._

  implicit val timeout = Timeout(QueryTimeout)
  
  log.info("Local DSLink Manager created")

  /**
   * Sends a message to the DSLink using ActorSelection.
   */
  def tellDSLink(linkName: String, msg: Any)(implicit sender: ActorRef = Actor.noSender) =
    select(linkName).tell(msg, sender)

  /**
   * Sends a request-response message to the DSLink using ActorSelection.
   */
  def askDSLink[T: ClassTag](linkName: String, msg: Any)(implicit sender: ActorRef = Actor.noSender) =
    akka.pattern.ask(select(linkName), msg, sender).mapTo[T]

  /**
   * Sends the message to an actor at the path `/user/broker/path`.
   */
  def tellNode(path: String, message: Any)(implicit sender: ActorRef = Actor.noSender) =
    if (path == Settings.Paths.Downstream)
      system.actorSelection("/user/backend") ! message
    else
      system.actorSelection("/user/" + Settings.Nodes.Root + path) ! message

  def connectEndpoint(linkName: String, ep: ActorRef, ci: ConnectionInfo) =
    tellDSLink(linkName, ConnectEndpoint(ep, ci))

  def disconnectEndpoint(linkName: String, killEndpoint: Boolean = true) =
    tellDSLink(linkName, DisconnectEndpoint(killEndpoint))

  def getDSLinkInfo(linkName: String) = askDSLink[LinkInfo](linkName, GetLinkInfo)

  /**
   * TODO review this to see if we can avoid blocking
   * Creates an instance of [[ActorRefProxy]].
   */
  def getCommProxy(linkName: String) = {
    val downstream = system.actorSelection("/user/downstream")
    val ref = akka.pattern.ask(downstream, DownstreamActor.GetOrCreateDSLink(linkName)).mapTo[ActorRef]
    new ActorRefProxy(Await.result(ref, Duration.Inf))
  }

  /**
   * Selects a DSLink actor path.
   */
  private def select = path _ andThen system.actorSelection

  /**
   * Constucts Actor path for the specified link name.
   */
  private def path(linkName: String) = "/user/" + Nodes.Downstream + "/" + linkName
}