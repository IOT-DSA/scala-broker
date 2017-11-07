package models.bench

import akka.actor.{ Actor, ActorLogging }
import models.akka.{ CommProxy, ConnectionInfo }
import models.akka.DSLinkMode.{ DSLinkMode, Dual, Requester, Responder }
import models.akka.Messages.{ ConnectEndpoint, DisconnectEndpoint }
import models.akka.IntCounter

/**
 * Base class for benchmark endpoint actors.
 */
abstract class AbstractEndpointActor(linkName: String, mode: DSLinkMode, proxy: CommProxy)
  extends Actor with ActorLogging {

  val isRequester = mode == Requester || mode == Dual
  val isResponder = mode == Responder || mode == Dual

  val connInfo = ConnectionInfo(linkName + "0" * 44, linkName, isRequester, isResponder)
  
  protected val localMsgId = new IntCounter(1)

  override def preStart() = {
    proxy tell ConnectEndpoint(self, connInfo)
    log.info("[{}] started", linkName)
  }

  override def postStop() = {
    proxy tell DisconnectEndpoint
    log.info("[{}] stopped", linkName)
  }
}