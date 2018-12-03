package models.sdk.dsa

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{Behavior, LogMarker}
import akka.persistence.typed.scaladsl.{Effect, PersistentBehaviors}
import akka.util.Timeout
import models.rpc.DSAValue._
import models.rpc.{DSAResponse, ListRequest, RemoveRequest, ResponseMessage, SetRequest, StreamState}
import models.sdk.Implicits._
import models.sdk._
import models.sdk.node.NodeCommand._
import models.sdk.node.NodeEvent.{ChildrenRemoved, _}
import models.sdk.node.{NodeBehavior, NodeCommand, NodeStatus}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * DSA (top level) node behavior.
  */
object DSANodeBehavior {

  import ChangeMode._
  import DSANodeCommand._
  import DSANodeEvent._
  import Effect._

  private implicit val marker = LogMarker("dsa")

  implicit val timeout = Timeout(5 seconds)

  /**
    * Creates a DSA node handler.
    *
    * @param node
    * @return
    */
  def dsaCmdHandler(node: NodeRef): CmdH[DSANodeCommand, DSANodeEvent, DSANodeState] = {

    case (ctx, _, GetNodeAPI(ref)) =>
      ctx.info("node api requested")
      ref ! node
      none

    // split request bundle into individual requests
    case (ctx, _, ProcessRequests(requests, replyTo)) => none.thenRun { _ =>
      ctx.info("received {} request bundle, re-sending individually", requests.size)
      requests foreach (ctx.self ! ProcessRequest(_, replyTo))
    }

    /* set */

    case (ctx, _, pr @ ProcessRequest(SetRequest(rid, path, newValue, _), ref)) => none.thenRun { _ =>
      logRequest(ctx, pr)
      val target = DSATarget.parse(ctx, path)
      val cmd = target.item match {
        case Some(DSATarget.Item(name, true))  => PutAttribute(name, newValue)
        case Some(DSATarget.Item(name, false)) => PutConfig(name, newValue)
        case _                                 => SetValue(Some(newValue))
      }
      target ! cmd
      ref ! ResponseMessage(0, None, DSAResponse(rid, Some(StreamState.Closed)) :: Nil)
    }

    /* list */

    case (ctx, state, pr @ ProcessRequest(ListRequest(rid, path), ref)) =>
      logRequest(ctx, pr)
      val origin = Origin(ref, rid)
      state.rids.get(path).fold[Effect[DSANodeEvent, DSANodeState]] {
        ctx.info("no subscription found for [{}], creating a new one", path)
        persist(ListSubscriptionCreated(path, origin)).thenRun { _ =>
          setupListSubscription(ctx, path, origin)(ctx.executionContext)
        }
      } { _ =>
        ctx.info("existing subscription found for [{}], adding a new subscriber", path)
        persist(ListOriginAdded(path, origin)).thenRun { state =>
          for {
            subscription <- state.rids.get(path)
            info <- subscription.listInfo
          } {
            ctx.info("delivering {} to {}", info, origin.listener)
            sendListInfoToOrigins(info, Set(origin))
          }
        }
      }

    /* remove */

    case (ctx, _, pr @ ProcessRequest(RemoveRequest(rid, path), ref)) => none.thenRun { _ =>
      logRequest(ctx, pr)
      val target = DSATarget.parse(ctx, path)
      val cmd = target.item match {
        case Some(DSATarget.Item(name, true))  => RemoveAttribute(name)
        case Some(DSATarget.Item(name, false)) => RemoveConfig(name)
        case _                                 => throw new IllegalArgumentException("Invalid path to remove: " + path)
      }
      target ! cmd
      ref ! ResponseMessage(0, None, DSAResponse(rid, Some(StreamState.Closed)) :: Nil)
    }

    case (ctx, _, msg @ DeliverListInfo(path, info)) =>
      logRequest(ctx, msg)
      persist(ListInfoDelivered(path, info)).thenRun { state =>
        val origins = state.rids.get(path).toList.flatMap(_.origins)
        ctx.info("delivering {} to {} origin(s)", info, origins.size)
        sendListInfoToOrigins(info, origins)
      }

    case (ctx, _, msg @ DeliverAttributeEvent(path, evt)) =>
      logRequest(ctx, msg)
      persist(AttributeEventDelivered(path, evt)).thenRun { state =>
        val origins = state.rids.get(path).toList.flatMap(_.origins)
        evt match {
          case AttributeAdded(name, value) =>
            sendListUpdatesToOrigins(List(array(name, value)), origins)
          case AttributeRemoved(name)      =>
            sendListUpdatesToOrigins(List(obj("name" -> name, "change" -> REMOVE)), origins)
          case AttributesChanged(_)        => //TODO: currently we do not handle this in DSA
        }
      }

    case (ctx, _, msg @ DeliverConfigEvent(path, evt)) =>
      logRequest(ctx, msg)
      persist(ConfigEventDelivered(path, evt)).thenRun { state =>
        val origins = state.rids.get(path).toList.flatMap(_.origins)
        evt match {
          case ConfigAdded(name, value) =>
            sendListUpdatesToOrigins(List(array(name, value)), origins)
          case ConfigRemoved(name)      =>
            sendListUpdatesToOrigins(List(obj("name" -> name, "change" -> REMOVE)), origins)
          case ConfigsChanged(_)        => //TODO: currently we do not handle this in DSA
        }
      }
    case (ctx, _, msg @ DeliverChildEvent(path, evt))  =>
      logRequest(ctx, msg)
      persist(ChildEventDelivered(path, evt)).thenRun { state =>
        val origins = state.rids.get(path).toList.flatMap(_.origins)
        evt match {
          case ChildAdded(name)   =>
            sendListUpdatesToOrigins(List(array(name, toUpdateValue(newNodeStatus(name)))), origins)
          case ChildRemoved(name) =>
            sendListUpdatesToOrigins(List(obj("name" -> name, "change" -> REMOVE)), origins)
          case ChildrenRemoved    => //TODO: currently we do not handle this in DSA
        }
      }
  }

  /**
    * DSA node event handler.
    */
  val dsaEventHandler: EvtH[DSANodeState, DSANodeEvent] = {
    case (state, ListSubscriptionCreated(path, origin)) =>
      state.copy(rids = state.rids + (path -> ListSubscription(origin)))
    case (state, ListOriginAdded(path, origin))         =>
      val subscription = state.rids(path)
      state.copy(rids = state.rids + (path -> subscription.addOrigin(origin)))
    case (state, ListInfoDelivered(path, info))         =>
      val subscription = state.rids(path)
      state.copy(rids = state.rids + (path -> subscription.withListInfo(info)))
    case (state, AttributeEventDelivered(path, evt))    =>
      val subscription = state.rids(path)
      state.copy(rids = state.rids + (path -> subscription.withAttributeEvent(evt)))
    case (state, ConfigEventDelivered(path, evt))       =>
      val subscription = state.rids(path)
      state.copy(rids = state.rids + (path -> subscription.withConfigEvent(evt)))
    case (state, ChildEventDelivered(path, evt))        =>
      val subscription = state.rids(path)
      state.copy(rids = state.rids + (path -> subscription.withChildEvent(evt)))
  }

  /**
    * Creates a dsa node behavior.
    *
    * @return
    */
  def dsaNode(): Behavior[DSANodeCommand] =
    Behaviors.setup { ctx =>
      ctx.info("DSA node created")
      val node = ctx.spawn(NodeBehavior.node(None), RootNode)
      PersistentBehaviors.receive[DSANodeCommand, DSANodeEvent, DSANodeState](
        persistenceId = ctx.self.path.toStringWithoutAddress,
        emptyState = DSANodeState.Empty,
        commandHandler = (ctx, state, cmd) => dsaCmdHandler(node)((ctx, state, cmd)),
        eventHandler = (state, event) => dsaEventHandler((state, event))
      )
    }

  /**
    * Logs incoming request.
    *
    * @param ctx
    * @param cmd
    */
  private def logRequest(ctx: ActorContext[_], cmd: Any): Unit = ctx.info("received {}", cmd)

  /**
    * Retrieves a complete LIST info and subscribes for attribute/config/child update events.
    *
    * @param ctx
    * @param path
    * @param origin
    * @param ec
    */
  private def setupListSubscription(ctx: ActorContext[DSANodeCommand], path: String, origin: Origin)
                                   (implicit ec: ExecutionContext) = {
    val target = DSATarget.parse(ctx, path)
    for {
      node <- target.resolve[NodeCommand]
      info <- retrieveListInfo(ctx, node)
    } {
      node ! AddAttributeListener(ctx.messageAdapter(evt => DeliverAttributeEvent(path, evt)))
      node ! AddConfigListener(ctx.messageAdapter(evt => DeliverConfigEvent(path, evt)))
      node ! AddChildListener(ctx.messageAdapter(evt => DeliverChildEvent(path, evt)))
      ctx.self ! DeliverListInfo(path, info)
    }
  }
  
  /**
    * Retrieve the complete LIST information from a node.
    *
    * @param ctx
    * @param node
    * @return
    */
  private def retrieveListInfo(ctx: ActorContext[DSANodeCommand], node: NodeRef) = {
    implicit val ec = ctx.executionContext
    implicit val scheduler = ctx.system.scheduler

    for {
      status <- node ? GetStatus
      children <- node ? GetChildren
      childStatuses <- Future.sequence(children.map(_ ? GetStatus))
    } yield ListInfo(node, status, childStatuses.map(s => s.name -> s).toMap)
  }

  /**
    * Converts a string->value map into a DSA-compatible list of update rows.
    *
    * @param data
    * @return
    */
  private def toUpdateRows(data: DSAMap) = data map {
    case (name, value) => array(name, value)
  } toList

  /**
    * Converts a node status instance into a DSA compatible update value.
    *
    * @param status
    * @return
    */
  private def toUpdateValue(status: NodeStatus) = MapValue(status.fullConfigs)

  /**
    * Converts a list info instance into a sequence of update rows compatible with LIST response.
    *
    * @param info
    * @return
    */
  private def listInfo2updates(info: ListInfo) = {
    val cfgUpdates = toUpdateRows(info.status.configs)
    val attrUpdates = toUpdateRows(info.status.attributes)
    val childUpdates = toUpdateRows(info.children.mapValues(toUpdateValue))

    cfgUpdates ++ attrUpdates ++ childUpdates
  }

  /**
    * Delivers the full LIST response back to listeners.
    *
    * @param info
    * @param origins
    */
  private def sendListInfoToOrigins(info: ListInfo, origins: Iterable[Origin]) = {
    val updates = Some(listInfo2updates(info))
    origins foreach { origin =>
      val response = DSAResponse(origin.id, Some(StreamState.Open), updates)
      origin.listener ! ResponseMessage(0, None, response :: Nil)
    }
  }

  /**
    * Delivers a LIST update back to listeners.
    *
    * @param updates
    * @param origins
    */
  private def sendListUpdatesToOrigins(updates: List[DSAVal], origins: Iterable[Origin]) = {
    origins foreach { origin =>
      val response = DSAResponse(origin.id, Some(StreamState.Open), Some(updates))
      origin.listener ! ResponseMessage(0, None, response :: Nil)
    }
  }
}