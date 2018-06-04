package models.api

import akka.actor.{ActorRef, ActorSystem, TypedActor, TypedProps}
import akka.cluster.Cluster
import akka.cluster.ddata.ReplicatedData
import models.api.DSAValueType.DSAValueType
import models.rpc.DSAValue.{DSAMap, DSAVal, obj, array}
import akka.cluster.ddata.Replicator._
import akka.event.Logging
import akka.pattern.PromiseRef
import akka.util.Timeout
import models.{RequestEnvelope, ResponseEnvelope, Settings}
import models.api.DistributedDSANode.DistributedDSANodeData
import models.api.DistributedNodesRegistry.{AddNode, GetNodesByPath}
import akka.pattern.ask
import models.rpc.DSAValue
import scala.concurrent.duration._

import scala.concurrent.{ExecutionContext, Future}

class DistributedDSANode(
                          val path: String,
                          val parent: Option[DSANode],
                          var initialVal: DSAVal,
                          val registry: ActorRef,
                          val replicator: ActorRef
                        )(implicit cluster: Cluster, system: ActorSystem) extends DSANode
  with TypedActor.Receiver
  with TypedActor.PreStart
  with TypedActor.PostStop
  with DSANodeSubscriptions
  with DSANodeRequestHandler {

  val dataKey = DistributedDSANodeKey(path)

  protected val log = Logging(TypedActor.context.system, getClass)

  protected def ownId = s"ddNode[$path]"

  override protected var _sids: Map[Int, ActorRef] = Map.empty
  override protected var _rids: Map[Int, ActorRef] = Map.empty
  override implicit val executionContext: ExecutionContext = TypedActor.context.dispatcher


  val name = path.split("/").last
  implicit val sender = TypedActor.context.self

  override protected def _configs: Map[String, DSAVal] = data.configs

  override protected def _attributes: Map[String, DSAVal] = data.attributes

  protected var _children: Map[String, DSANode] = Map()
  var _action: Option[DSAAction] = None

  replicator ! Subscribe(dataKey, sender)

  private[this] var data = DistributedDSANodeData(initialVal)

  val timeout = Settings.QueryTimeout

  implicit val implicitTimeout = Timeout(timeout)

  override def value: Future[DSAVal] = Future.successful(data.value)

  override def value_=(v: DSAVal): Unit = editProperty { old =>
    old.copy(value = old.value.withValue(v))
  }


  override def valueType: Future[DSAValueType] = Future.successful(data
    .configs
    .get("$type")
    .map(_.value.asInstanceOf[String])
    .map(DSAValueType.byName)
    .getOrElse(DSAValueType.DSAString)
  )

  override def valueType_=(vt: DSAValueType): Unit = editProperty { old =>
    old.copy(configs = old.configs + ("$type" -> vt.toString))
  }


  override def displayName: Future[String] = Future.successful(
    data.configs.get("$name")
      .map(_.value.asInstanceOf[String])
      .getOrElse("")
  )

  override def displayName_=(name: String): Unit = editProperty { old =>
    old.copy(configs = old.configs + ("$name" -> name))
  }


  override def profile: String = data.configs.get("$it")
    .map(_.value.asInstanceOf[String])
    .getOrElse("")


  override def profile_=(p: String): Unit = editProperty { old =>
    old.copy(configs = old.configs + ("$it" -> p))
  }


  override def configs: Future[Map[String, DSAVal]] = Future.successful(data.configs)

  override def config(name: String): Future[Option[DSAVal]] = Future.successful(data.configs.get(name))

  override def addConfigs(cfg: (String, DSAVal)*): Unit = editProperty { old =>
    val newConf = cfg.map(addSuffix("$")).foldLeft(old.configs)((c, next) => c + next)
    old.copy(configs = newConf)
  }

  override def removeConfig(name: String): Unit = editProperty { old =>
    old.copy(configs = old.configs - name)
  }

  override def attributes: Future[Map[String, DSAVal]] = Future.successful(data.attributes)

  override def attribute(name: String): Future[Option[DSAVal]] = Future.successful(data.attributes.get(name))

  override def addAttributes(cfg: (String, DSAVal)*): Unit = editProperty { old =>
    old.copy(attributes = cfg.map(addSuffix("@")).foldLeft(old.attributes)((a, next) => a + next))
  }

  override def removeAttribute(name: String): Unit = editProperty { old =>
    old.copy(attributes = old.attributes - name)
  }

  override def children: Future[Map[String, DSANode]] = Future.successful(_children)

  override def child(name: String): Future[Option[DSANode]] = Future.successful(_children.get(name))

  override def addChild(name: String): Future[DSANode] = {
    _children.get(name).map(Future.successful).getOrElse {
      (registry ? AddNode(s"$path/$name")).mapTo[DSANode] flatMap { actor =>
        log.debug(s"Adding child: $name ->  $actor")
        addChild(name, actor)
      }
    }
  }


  override def addChild(name: String, node: DSANode): Future[DSANode] = {

    if (_children.get(name).isDefined) {
      val df = 123
    }

    log.debug(s"children before: ${_children}")
    log.debug(s"Adding child: $name ->  $node")
    _children += (name -> node)
    log.debug(s"children after: ${_children}")

    editProperty({ old =>
      old.copy(children = old.children + name)
    })

    Future.successful(node)
  }

  override def removeChild(name: String): Unit = {
    editProperty { old =>
      old.copy(children = old.children - name)
    }
  }

  override def action: Option[DSAAction] = _action

  override def action_=(a: DSAAction): Unit = _action = Some(a)

  override def invoke(params: DSAMap): Unit = _action foreach (_.handler(ActionContext(this, params)))

  override def subscribe(sid: Int, ref: ActorRef): Unit = editProperty { old =>
    old.copy(subscriptions = old.subscriptions + (sid -> ref))
  }

  override def unsubscribe(sid: Int): Unit = editProperty { old =>
    old.copy(subscriptions = old.subscriptions - sid)
  }

  override def list(rid: Int, ref: ActorRef): Unit = editProperty { old =>
    old.copy(listSubscriptions = old.listSubscriptions + (rid -> ref))
  }

  override def unlist(rid: Int): Unit = editProperty { old =>
    old.copy(listSubscriptions = old.listSubscriptions - rid)
  }

  private[this] def isLocal(ref: ActorRef): Boolean = ref.path.address == sender.path.address


  private[this] def updateLocalState(update: ReplicatedData) = update match {
    case d: DistributedDSANodeState =>
      val newData = toLocalData(d)

      val createdChildren = newData.children.diff(data.children)


      (registry ? GetNodesByPath(createdChildren.map(ch => s"$path/$ch"))).mapTo[Map[String, DSANode]]
        .foreach({ ch =>
          val newChildren = createdChildren.map { n => array(n, obj("$is" -> "node")) }

          val attrUpdates = newData.attributes
            .filter { case (k, v) => !data.attributes.get(k).isDefined || data.attributes(k) != v }
            .map { case (k, v) => array(k, v) }

          val confUpdates = newData.configs
            .filter { case (k, v) => !data.configs.get(k).isDefined || data.configs(k) != v }
            .map { case (k, v) => array(k, v) }

          val deleted = (
            data.children.diff(newData.children) ++ data.attributes
              .keySet.diff(newData.attributes.keySet) ++ data.configs
              .keySet.diff(newData.configs.keySet)
            ).map(n => obj("name" -> n, "change" -> "remove"))


          val updates = newChildren ++ attrUpdates ++ confUpdates ++ deleted

          if (!updates.isEmpty) {
            notifyListActors(updates)
          }

          _sids = newData.subscriptions.filter(kv => isLocal(kv._2))
          _rids = newData.listSubscriptions.filter(kv => isLocal(kv._2))
          if (data.value != newData.value) notifySubscribeActors(newData.value)
          data = newData
          log.debug("data replicated: {}", data)
        })

    case _ =>
      log.warning("Unsupported data type: {}", data)
  }

  private[this] def toLocalData(d: DistributedDSANodeState): DistributedDSANodeData = DistributedDSANodeData(
    d.value.value,
    d.configs.entries,
    d.attributes.entries,
    d.subscriptions.entries,
    d.listSubscriptions.entries,
    d.children.elements
  )

  private[this] def addSuffix(suffix: String)(tuple: (String, DSAValue.DSAVal)): (String, DSAValue.DSAVal) = {
    (if (tuple._1.startsWith(suffix)) tuple._1 else suffix + tuple._1) -> tuple._2
  }


  override def onReceive(message: Any, sender: ActorRef): Unit = {
    message match {
      case g@GetSuccess(dataKey, Some(promiseRef)) => promiseRef match {
        case pr: PromiseRef[Any] =>
          pr.promise.success(g.get(dataKey))
        case _ =>
      }
      case NotFound(dataKey, Some(promiseRef)) => promiseRef match {
        case pr: PromiseRef[_] =>
          log.warning("Couldn't find element by key: {}", dataKey)
          pr.promise.failure(new RuntimeException("Couldn't find element by key: " + dataKey))
        case _ =>
      }
      case e@RequestEnvelope(requests) =>
        log.info("{}: received {}", ownId, e)
        val responses = requests flatMap handleRequest(sender)
        sender ! ResponseEnvelope(responses)
      case u: UpdateResponse[_] ⇒ // ignore
        log.debug(s"$ownId: state successfully updated: ${u.key}")
      case c@Changed(dataKey) ⇒
        updateLocalState(c.get(dataKey))
        log.debug("Current elements: {}", c.get(dataKey))
    }
  }

  override def preStart(): Unit = {
    log.info("{} initialized", ownId)
  }

  override def postStop(): Unit = {
    log.info("{} stopped", ownId)
  }

  private[this] def editProperty[T](transform: DistributedDSANodeState => DistributedDSANodeState, maybePromise: Option[PromiseRef[T]] = None) =
    replicator ! Update(dataKey, empty, writeLocal, maybePromise)(transform)

  private[this] def editPropertySync[T](transform: DistributedDSANodeState => DistributedDSANodeState, maybePromise: Option[PromiseRef[T]] = None) =
    replicator ! Update(dataKey, empty, WriteAll(timeout), maybePromise)(transform)

  private[this] def empty = DistributedDSANodeState.empty
}

/**
  * Factory for [[DistributedDSANode]] instances.
  */
object DistributedDSANode {

  case class DistributedDSANodeData(value: DSAVal,
                                    configs: Map[String, DSAVal] = Map("$is" -> "node"),
                                    attributes: Map[String, DSAVal] = Map(),
                                    subscriptions: Map[Int, ActorRef] = Map(),
                                    listSubscriptions: Map[Int, ActorRef] = Map(),
                                    children: Set[String] = Set())

  case class DiffReport[A](created: Map[String, A], updated: Map[String, A], removed: Set[String])


  /**
    * Creates a new [[InMemoryDSANode]] props instance.
    */
  def props(path: String, parent: Option[DSANode], initialVal: DSAVal, registry: ActorRef, replicator: ActorRef)
           (implicit cluster: Cluster, system: ActorSystem, executionContext: ExecutionContext) =
    TypedProps(classOf[DSANode], new DistributedDSANode(path: String, parent, initialVal, registry, replicator))
}
