package models.api

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, TypedActor, TypedProps}
import akka.cluster.Cluster
import akka.cluster.ddata.ReplicatedData
import models.api.DSAValueType.{DSADynamic, DSAValueType}
import models.rpc.DSAValue.{ArrayValue, DSAMap, DSAVal, StringValue, array, obj}
import akka.cluster.ddata.Replicator._
import akka.event.Logging
import akka.pattern.{PromiseRef, ask}
import akka.util.Timeout
import models.akka.Messages.{AppendDsId2Token, GetTokens}
import models.{RequestEnvelope, ResponseEnvelope, Settings}
import models.api.DistributedDSANode.DistributedDSANodeData
import models.api.DistributedNodesRegistry.{AddNode, GetNodesByDescription}
import models.rpc.DSAValue
import models.util.LoggingAdapterInside

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

case class DSANodeDescription(path: String, attrAndConf: Map[String, DSAVal] = Map(), value: DSAVal = StringValue("")) {
  def profile: Option[String] = attrAndConf.get("$is").flatMap(strType)

  def valueType: Option[DSAValueType] = attrAndConf.get("$type").flatMap { v =>
    strType(v).map(DSAValueType.byName)
  }

  private def strType(value: DSAVal): Option[String] = value match {
    case str: DSAValue[String] => Some(str.value)
    case _ => None
  }

}

case class InitMe()

object DSANodeDescription {
  def init(path: String, profile: Option[String] = None, valueType: Option[DSAValueType] = None): DSANodeDescription = {
    val node = DSANodeDescription(path)
    val withProfile = node.copy(attrAndConf = node.attrAndConf + ("$is" -> profile.getOrElse("node")))
    val withType = valueType
      .map(vType => withProfile.copy(attrAndConf = withProfile.attrAndConf + ("$type" -> vType.toString)))
      .getOrElse(withProfile)
    withType
  }
}

class DistributedDSANode(_parent: Option[DSANode],
                         initialVal: DSAVal,
                         nodeDescription: DSANodeDescription,
                         val registry: ActorRef,
                         val replicator: ActorRef
                        )(implicit cluster: Cluster, system: ActorSystem) extends DSANode
  with TypedActor.Receiver
  with TypedActor.PreStart
  with TypedActor.PostStop
  with DSANodeSubscriptions
  with LoggingAdapterInside
  with DSANodeRequestHandler {

  override def parent: Option[DSANode] = _parent

  override def path: String = nodeDescription.path

  val dataKey = DistributedDSANodeKey(path)

  val _system: ActorSystem = system

  //actors behaviors would be better, but we work with typed actor
  private var stateInitialized = false

  protected val log = Logging(TypedActor.context.system, getClass)

  protected def ownId = s"ddNode[$path]"

  private var stash: Vector[Update[DistributedDSANodeState]] = Vector()
  override protected var _sids: Map[Int, ActorRef] = Map.empty
  override protected var _rids: Map[Int, ActorRef] = Map.empty
  override implicit val executionContext: ExecutionContext = TypedActor.context.dispatcher
  val name: String = path.split("/").last
  implicit val self: ActorRef = TypedActor.context.self
  private[this] var data = DistributedDSANode.initialData(nodeDescription, initialVal, name)

  override protected def _configs: Map[String, DSAVal] = data.configs

  override protected def _attributes: Map[String, DSAVal] = data.attributes

  protected var _children: Map[String, DSANode] = Map()

  var _action: Option[DSAAction] = None

  replicator ! Subscribe(dataKey, self)

  val timeout: FiniteDuration = Settings.QueryTimeout

  implicit val implicitTimeout: Timeout = Timeout(timeout)

  override def value: Future[DSAVal] = Future.successful(data.value.getOrElse(DSAValue.StringValue("")))

  override def value_=(v: DSAVal): Unit = editProperty { old =>
    old.copy(value = old.value.withValue(Some(v)))
  }


  override def valueType: Future[DSAValueType] = Future.successful(valueTypeSync.getOrElse(DSADynamic))

  private def valueTypeSync = data
    .configs
    .get("$type")
    .map(_.value.asInstanceOf[String])
    .map(DSAValueType.byName)

  override def valueType_=(vt: DSAValueType): Unit = editProperty { old =>
    old.copy(configs = old.configs + ("$type" -> Some(vt.toString)))
  }

  override def displayName: Future[String] = Future.successful(
    data.configs.get("$name")
      .map(_.value.asInstanceOf[String])
      .getOrElse(name)
  )

  override def displayName_=(name: String): Unit = editProperty { old =>
    old.copy(configs = old.configs + ("$name" -> Some(name)))
  }


  override def profile: String = data.configs.get("$is")
    .map(_.value.asInstanceOf[String])
    .getOrElse("node")


  override def profile_=(p: String): Unit = editProperty { old =>
    old.copy(configs = old.configs + ("$is" -> Some(p)))
  }


  override def configs: Future[Map[String, DSAVal]] = Future.successful(data.configs)

  override def config(name: String): Future[Option[DSAVal]] = Future.successful(data.configs.get(name))

  override def addConfigs(cfg: (String, DSAVal)*): Unit = {
    editProperty { old =>
      val newConf = cfg.map(addSuffix("$")).foldLeft(old.configs)((c, next) => c + (next._1 -> Some(next._2)))
      old.copy(configs = newConf)
    }
  }

  override def removeConfig(name: String): Unit = {
    editProperty { old =>
      old.copy(configs = old.configs + (name -> None))
    }
  }

  override def attributes: Future[Map[String, DSAVal]] = Future.successful(data.attributes)

  override def attribute(name: String): Future[Option[DSAVal]] = Future.successful(data.attributes.get(name))

  override def addAttributes(cfg: (String, DSAVal)*): Unit = editProperty { old =>
    old.copy(attributes = cfg.map(addSuffix("@")).foldLeft(old.attributes)((a, next) => a + (next._1 -> Some(next._2))))
  }

  override def removeAttribute(name: String): Unit = editProperty { old =>
    old.copy(attributes = old.attributes + (name -> None))
  }

  override def children: Future[Map[String, DSANode]] = Future.successful(_children)

  override def child(name: String): Future[Option[DSANode]] = Future.successful(_children.get(name))

  override def addChild(name: String, profile: Option[String] = None, valueType: Option[DSAValueType] = None): Future[DSANode] = synchronized {
    _children.get(name).map(Future.successful).getOrElse {
      val description = DSANodeDescription.init(s"$path/$name", profile, valueType)
      (registry ? AddNode(description)).mapTo[DSANode] flatMap { actor =>
        log.debug("Adding child: {} ->  {}", name, actor)
        addChild(name, actor)
      }
    }
  }

  override def addChild(name: String, node: DSANode): Future[DSANode] = {
    _children += (name -> node)

    editProperty({ old =>
      old.copy(children = old.children + (name -> Some(DSANodeDescription.init(node.path, Some(node.profile), valueTypeSync))))
    })

    Future.successful(node)
  }

  override def addChild(name: String, paramsAndConfigs: (String, DSAVal)*): Future[DSANode] = synchronized {
    _children.get(name).map(Future.successful).getOrElse {
      val map: Map[String, DSAVal] = paramsAndConfigs.toMap
      val description = DSANodeDescription(s"$path/$name", map)
      (registry ? AddNode(description)).mapTo[DSANode] flatMap { actor =>
        log.debug("Adding child: {} ->  {}", name, actor)
        addChild(name, actor)
      }
    }
  }

  override def removeChild(name: String): Unit = {
    editProperty { old =>
      old.copy(children = old.children + (name -> None))
    }
  }

  override def action: Option[DSAAction] = _action

  override def action_=(a: DSAAction): Unit = {
    _action = Some(a)
  }

  override def invoke(params: DSAMap): Any = {
    implicit val system: ActorSystem = TypedActor.context.system
    _action map { a =>
      a.handler(ActionContext(this, params))
    }
  }

  override def subscribe(sid: Int, ref: ActorRef): Unit = editProperty { old =>
    old.copy(subscriptions = old.subscriptions + (sid -> Some(ref)))
  }

  override def unsubscribe(sid: Int): Unit = editProperty { old =>
    old.copy(subscriptions = old.subscriptions + (sid -> None))
  }

  override def list(rid: Int, ref: ActorRef): Unit = editProperty { old =>
    old.copy(listSubscriptions = old.listSubscriptions + (rid -> Some(ref)))
  }

  override def unlist(rid: Int): Unit = editProperty { old =>
    old.copy(listSubscriptions = old.listSubscriptions + (rid -> None))
  }

  private[this] def isLocal(ref: ActorRef): Boolean = ref.path.address == self.path.address


  private[this] def updateLocalState(update: ReplicatedData): Unit = update match {
    case d: DistributedDSANodeState =>

      val diff = collectDiff(d)

      val createdChildren = diff.childrenDiff
        .created
        .filter(kv => kv._2.attrAndConf.get("$is").isDefined)

      val createdNodes: Future[Map[String, DSANode]] = if (createdChildren.isEmpty) {
        Future.successful(Map())
      } else {
        (registry ? GetNodesByDescription(createdChildren.values.toSeq)).mapTo[Map[String, DSANode]]
      }

      createdNodes.foreach({ ch =>

        val report = updatesReport(diff)

        _children --= diff.childrenDiff.deleted
        _sids = foldDiff(_sids, diff.subscriptionsDiff).filter(kv => isLocal(kv._2))
        _rids = foldDiff(_rids, diff.listSubscriptionsDiff).filter(kv => isLocal(kv._2))

        if(report.nonEmpty){
          log.debug("sending updates to list subscriptions: {}", report)
          notifyListActors(report.toArray[DSAVal]: _*)
        }

        if(diff.valueDiff.isDefined) notifySubscribeActors(diff.valueDiff.get.getOrElse(StringValue("")))

        data = newDataFromDiff(diff)
      })

    case _ =>
      log.warning("Unsupported data type: {}", data)
  }

  private[this] def addSuffix(suffix: String)(tuple: (String, DSAValue.DSAVal)): (String, DSAValue.DSAVal) = {
    (if (tuple._1.startsWith(suffix)) tuple._1 else suffix + tuple._1) -> tuple._2
  }


  override def onReceive(message: Any, sender: ActorRef): Unit = {
    message match {
      case g@GetSuccess(dataKey, Some(msg)) => msg match {
        case InitMe =>
          updateLocalState(g.get(dataKey))
          stash.foreach {
            replicator ! _
          }
          stash = Vector()
          //actors behaviors would be better, but we work with typed actor
          stateInitialized = true
          log.info("{} initialized with data: {}", ownId, data)
        case a: Any =>
          log.warning("handler for message is not implemented:{}:{}", a, a.getClass)
      }
      case NotFound(dataKey, Some(msg)) => msg match {
        case InitMe =>
          stash.foreach {
            replicator ! _
          }
          stash = Vector()
          //actors behaviors would be better, but we work with typed actor
          stateInitialized = true
          log.info("{} initialized from the scratch", ownId)
        case a: Any =>
          log.warning("handler for message is not implemented:{}:{}", a, a.getClass)
      }
      case e @ RequestEnvelope(requests, _)      =>
        log.debug("{}: received {}", ownId, e)
        val responses = requests flatMap handleRequest(sender)
        sender ! ResponseEnvelope(responses)
      case u: UpdateResponse[_] ⇒ // ignore
        log.debug("{}: state successfully updated: {}", ownId, u.key)
      case c@Changed(dataKey) ⇒
        log.debug("Current elements: {}", c.get(dataKey))
        updateLocalState(c.get(dataKey))
      // TODO: Extract following 2 methods (In the InMemoryDSANode too) into separated trait
      case AppendDsId2Token(name, value) =>
        appendDsId2config(name, value)
      case GetTokens =>
        getTokens(sender)
      case a: Any =>
        log.warning("unhandled  message: {}", a)
    }
  }

  private def getTokens(sender: ActorRef): Unit = {
    log.info(s"$ownId: GetTokens received")

    val fResponse = children.map { m =>
      m.values.filter(node => node.action.isEmpty).toList
    }
    val response = Await.result(fResponse, Duration.Inf)
    sender ! response
  }

  private def appendDsId2config(name: String, value: String) = {
    log.info(s"$ownId: received AppendDsId2Token ($name, $value)")
    if (name.startsWith("$")) {

      val fIds = config(name)

      val v = fIds.onComplete { item =>
        val values: DSAVal = item.get match {
          case None => array(value)
          case Some(arr) =>
            val srcVal = arr.asInstanceOf[ArrayValue].value.toSeq
            if (srcVal.contains(StringValue(value)))
              array(value)
            else
              srcVal ++ Seq(StringValue(value))
        }
        addConfigs(name -> values)
      }

    } else
      log.warning("UpdateToken's parameter does not contains '" + name + "' variable name")
  }

  override def preStart(): Unit = {
    // 5 minutes we can't use standard one - it's too short. We can't use infinite - it's too long
    val initTimeout: FiniteDuration = FiniteDuration(5, TimeUnit.SECONDS)
    replicator ! Get(dataKey, readLocal, Some(InitMe))
  }

  override def postStop(): Unit = {
    replicator ! Unsubscribe(dataKey, self)
    log.info("{} stopped", ownId)
  }

  def newDataFromDiff(diff: DataDiff): DistributedDSANodeData = {
    data.copy(
      value = if(diff.valueDiff.isDefined) diff.valueDiff.get else data.value,
      configs = foldDiff(data.configs, diff.configsDiff),
      attributes = foldDiff(data.attributes, diff.attributesDiff),
      subscriptions = foldDiff(data.subscriptions, diff.subscriptionsDiff),
      listSubscriptions = foldDiff(data.listSubscriptions, diff.listSubscriptionsDiff),
      children = foldDiff(data.children, diff.childrenDiff)
    )
  }

  def foldDiff[K, V](initial:Map[K,V], diff:DiffSet[K, V]):Map[K,V] = {
    val withNew = initial ++ diff.created
    val withUpdated = withNew ++ diff.updated
    withUpdated -- diff.deleted
  }

  private[this] def editProperty[T](transform: DistributedDSANodeState => DistributedDSANodeState, maybePromise: Option[PromiseRef[T]] = None) = {
    // we don't want state to be send with empty state as new
    val u = Update(dataKey, empty, writeLocal, maybePromise)(transform)
    if (stateInitialized) {
      replicator ! u
    } else {
      stash = stash :+ u
    }
  }

  private[this] def empty = DistributedDSANodeState.empty

  def collectDiff(state: DistributedDSANodeState): DataDiff = DataDiff(
    valueDiff = if (state.value.value != data.value) Some(state.value.value) else None,
    configsDiff = collectDiffSet(state.configs.entries, data.configs),
    attributesDiff = collectDiffSet(state.attributes.entries, data.attributes),
    listSubscriptionsDiff = collectDiffSet(state.listSubscriptions.entries, data.listSubscriptions),
    subscriptionsDiff = collectDiffSet(state.subscriptions.entries, data.subscriptions),
    childrenDiff = collectDiffSet(state.children.entries, data.children)
  )

  def collectDiffSet[K, V](newValues: Map[K, Option[V]], oldValues: Map[K, V]): DiffSet[K, V] = {
    val (deleted, upserted) = newValues.partition(_._2.isEmpty)
    try{
      val (created, other) = upserted.map(kv => (kv._1, kv._2.get)).partition { case (k, _) => oldValues.get(k).isEmpty }
      val updated = other.filter { case (key, value) => oldValues.get(key).isDefined && oldValues(key) != value }

      DiffSet(
        deleted = deleted.keys.toSet,
        created = created,
        updated = updated
      )
    } catch {
      case e:Exception =>
        DiffSet(Map(), Map(), Set())
    }

  }

  def updatesReport(diff: DataDiff): Iterable[DSAVal] = {

    val dsValMapper: ((String, DSAVal)) => DSAVal = {case (k, v) => array(k, v)}
    val dSANodeDescriptionMapper: ((String, DSANodeDescription)) => DSAVal = {case (k, v) => array(k, obj(v.attrAndConf.toSeq: _*))}

    val confDiff = collectReport[DSAVal](diff.configsDiff, dsValMapper)
    val attrDiff = collectReport[DSAVal](diff.attributesDiff, dsValMapper)
    val childrenDiff = collectReport[DSANodeDescription](diff.childrenDiff, dSANodeDescriptionMapper)

    confDiff ++ attrDiff ++ childrenDiff
  }

  def collectReport[T](diff: DiffSet[String, T], mapper: ((String, T)) => DSAVal): Iterable[DSAVal] = {
    (diff.created.map(mapper(_)) ++ diff.updated.map(mapper(_))) ++ diff.deleted.map(toDeleteReport)
  }

  def toDeleteReport(key: String): DSAVal = obj("name" -> key, "change" -> "remove")

}

case class DataDiff(valueDiff: Option[Option[DSAVal]],
                    configsDiff: DiffSet[String, DSAVal],
                    attributesDiff: DiffSet[String, DSAVal],
                    subscriptionsDiff: DiffSet[Int, ActorRef],
                    listSubscriptionsDiff: DiffSet[Int, ActorRef],
                    childrenDiff: DiffSet[String, DSANodeDescription])

case class DiffSet[K, V](created: Map[K, V], updated: Map[K, V], deleted: Set[K])

/**
  * Factory for [[DistributedDSANode]] instances.
  */
object DistributedDSANode {

  def initialData(nodeDescription: DSANodeDescription, value: DSAVal, name:String): DistributedDSANodeData = {
    DistributedDSANodeData(
      Some(value),
      configs = nodeDescription.attrAndConf.filter(_._1.startsWith("$")) + ("$name" -> StringValue(name)),
      attributes = nodeDescription.attrAndConf.filter(_._1.startsWith("@"))
    )
  }

  case class DistributedDSANodeData(value: Option[DSAVal],
                                    configs: Map[String, DSAVal] = Map("$is" -> "node"),
                                    attributes: Map[String, DSAVal] = Map(),
                                    subscriptions: Map[Int, ActorRef] = Map(),
                                    listSubscriptions: Map[Int, ActorRef] = Map(),
                                    children: Map[String, DSANodeDescription] = Map())

  case class DiffReport[A](created: Map[String, A], updated: Map[String, A], removed: Set[String])


  def props(parent: Option[DSANode],
            initialVal: DSAVal,
            nodeDescription: DSANodeDescription,
            registry: ActorRef,
            replicator: ActorRef
           )(implicit cluster: Cluster, system: ActorSystem, executionContext: ExecutionContext): TypedProps[DSANode] =
    TypedProps(classOf[DSANode], new DistributedDSANode(parent, initialVal, nodeDescription, registry, replicator))


  /**
    * Creates a new [[InMemoryDSANode]] props instance.
    */
  def props(path: String,
            parent: Option[DSANode],
            initialVal: DSAVal,
            profile: Option[String] = None,
            valueType: Option[DSAValueType] = None,
            registry: ActorRef,
            replicator: ActorRef)
           (implicit cluster: Cluster, system: ActorSystem, executionContext: ExecutionContext): TypedProps[DSANode] =
    props(parent, initialVal,
      DSANodeDescription.init(
        path,
        profile,
        valueType),
      registry,
      replicator)
}
