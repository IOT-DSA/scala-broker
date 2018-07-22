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
import models.akka.Messages.{GetTokens, AppendDsId2Token}
import models.{RequestEnvelope, ResponseEnvelope, Settings}
import models.api.DistributedDSANode.DistributedDSANodeData
import models.api.DistributedNodesRegistry.{AddNode, GetNodesByDescription}
import models.rpc.DSAValue
import models.util.LoggingAdapterInside

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

case class DSANodeDescription(path: String, attrAndConf: Map[String, DSAVal] = Map(), value: DSAVal = StringValue("")) {
  def profile: Option[String] = attrAndConf.get("$is").flatMap(strType _)

  def valueType: Option[DSAValueType] = attrAndConf.get("$type").flatMap { v =>
    strType(v).map(DSAValueType.byName(_))
  }

  private def strType(value: DSAVal): Option[String] = value match {
    case str: Option[DSAValue[String]] => Some(str.get.value)
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
  private var stateInitialized = false;

  protected val log = Logging(TypedActor.context.system, getClass)
  protected var initialData = nodeDescription.attrAndConf

  protected def ownId = s"ddNode[$path]"
  private var stash:Vector[Update[DistributedDSANodeState]] = Vector()
  override protected var _sids: Map[Int, ActorRef] = Map.empty
  override protected var _rids: Map[Int, ActorRef] = Map.empty
  override implicit val executionContext: ExecutionContext = TypedActor.context.dispatcher
  val name = path.split("/").last
  implicit val self = TypedActor.context.self
  private[this] var data = DistributedDSANode.initialData(nodeDescription, initialVal)

  override protected def _configs: Map[String, DSAVal] = data.configs

  override protected def _attributes: Map[String, DSAVal] = data.attributes

  protected var _children: Map[String, DSANode] = Map()

  var _action: Option[DSAAction] = None

  replicator ! Subscribe(dataKey, self)

  val timeout = Settings.QueryTimeout

  implicit val implicitTimeout = Timeout(timeout)

  override def value: Future[DSAVal] = Future.successful(data.value)

  override def value_=(v: DSAVal): Unit = editProperty { old =>
    old.copy(value = old.value.withValue(v))
  }

  override def valueType: Future[DSAValueType] = Future.successful(valueTypeSync.getOrElse(DSADynamic))

  private def valueTypeSync = data
    .configs
    .get("$type")
    .map(_.value.asInstanceOf[String])
    .map(DSAValueType.byName)

  override def valueType_=(vt: DSAValueType): Unit = editProperty { old =>
    old.copy(configs = old.configs + ("$type" -> vt.toString))
  }

  override def displayName: Future[String] = Future.successful(
    data.configs.get("$name")
      .map(_.value.asInstanceOf[String])
      .getOrElse(name)
  )

  override def displayName_=(name: String): Unit = editProperty { old =>
    old.copy(configs = old.configs + ("$name" -> name))
  }

  override def profile: String = data.configs.get("$is")
    .map(_.value.asInstanceOf[String])
    .getOrElse("node")

  override def profile_=(p: String): Unit = editProperty { old =>
    old.copy(configs = old.configs + ("$is" -> p))
  }


  override def configs: Future[Map[String, DSAVal]] = Future.successful(data.configs)

  override def config(name: String): Future[Option[DSAVal]] = Future.successful(data.configs.get(name))

  override def addConfigs(cfg: (String, DSAVal)*): Unit = {
    editProperty { old =>
      val newConf = cfg.map(addSuffix("$")).foldLeft(old.configs)((c, next) => c + next)
      old.copy(configs = newConf)
    }
  }

  override def removeConfig(name: String): Unit = {
    val default = initialData.get(name)

    default.foreach { value =>
      editProperty { old =>
        old.copy(configs = old.configs + (name -> value))
      }
    }
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

  override def addChild(name: String, profile: Option[String] = None, valueType: Option[DSAValueType] = None): Future[DSANode] = {
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
      old.copy(children = old.children + (name -> DSANodeDescription.init(node.path, Some(node.profile), valueTypeSync)))
    })

    Future.successful(node)
  }

  override def addChild(name: String, paramsAndConfigs: (String, DSAVal)*): Future[DSANode] = {
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
      old.copy(children = old.children - name)
    }
  }

  override def action: Option[DSAAction] = _action

  override def action_=(a: DSAAction) = {
    _action = Some(a)
  }

  override def invoke(params: DSAMap): Any = {
    implicit val system: ActorSystem = TypedActor.context.system
    _action map { a =>
      a.handler(ActionContext(this, params))
    }
  }

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

  private[this] def isLocal(ref: ActorRef): Boolean = ref.path.address == self.path.address


  private[this] def updateLocalState(update: ReplicatedData) = update match {
    case d: DistributedDSANodeState =>
      val newData = toLocalData(d)

      val createdChildren = newData
        .children
        .filter(kv => !data.children.contains(kv._1))
        .filter(kv => kv._2.attrAndConf.get("$is").isDefined) //in case of

      val createdNodes: Future[Map[String, DSANode]] = if (createdChildren.isEmpty) {
        Future.successful(Map())
      } else {
        (registry ? GetNodesByDescription(createdChildren.map(_._2).toSeq)).mapTo[Map[String, DSANode]]
      }

      createdNodes.foreach({ ch =>

        def defaultIfEmpty(in: String, default: String) = if (in == null || in.isEmpty) default else in

        val newChildren = createdChildren.map { case (path, description) =>
          array(path, obj(description.attrAndConf.toSeq: _*))
        }

        log.debug("new children \nnode:{} \nnewCHildren:{}", path, newChildren)

        val attrDiff = newData.attributes
          .filter { case (k, v) => !data.attributes.get(k).isDefined || data.attributes(k) != v }

        initialData ++= attrDiff.filter { case (k, _) => !initialData.contains(k) }

        val confDiff = newData.configs
          .filter { case (k, v) => !data.configs.get(k).isDefined || data.configs(k) != v }

        initialData ++= confDiff.filter { case (k, _) => !initialData.contains(k) }

        val removedAttr = data.attributes
          .keySet.diff(newData.attributes.keySet)

        val removedConf = data.configs
          .keySet.diff(newData.configs.keySet)

        //        val deleted = (
        //          data.children.keySet.diff(newData.children.keySet)
        //        ).map(n => obj("name" -> n, "change" -> "remove"))

        _sids = newData.subscriptions.filter(kv => isLocal(kv._2))
        _rids = newData.listSubscriptions.filter(kv => isLocal(kv._2))

        log.debug("replicating data for \nnode:{} \nold:{}, \nnew:{}", path, data, newData)

        val newAttr = newData.attributes ++ removedAttr
          .filter(initialData.contains(_))
          .map(k => (k -> initialData(k))).toMap[String, DSAVal]


        val newConf = newData.configs ++ removedAttr
          .filter(initialData.contains(_))
          .map(k => (k -> initialData(k))).toMap[String, DSAVal]

        val confUpdates = newConf.map { case (k, v) => array(k, v) }
        val attrUpdates = newAttr.map { case (k, v) => array(k, v) }

        if (data.value != newData.value) notifySubscribeActors(newData.value)

        data = data.copy(
          value = newData.value,
          configs = data.configs ++ newConf,
          attributes = data.attributes ++ newAttr,
          subscriptions = newData.subscriptions,
          listSubscriptions = newData.listSubscriptions,
          children = newData.children
        )

        val updates = newChildren ++ attrUpdates ++ confUpdates // ++ deleted

        if (!updates.isEmpty) {
          log.debug("sending updates to list subscriptions: {}", updates)
          notifyListActors(updates.toArray[DSAVal]: _*)
        }
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
    d.children.entries
  )

  private[this] def addSuffix(suffix: String)(tuple: (String, DSAValue.DSAVal)): (String, DSAValue.DSAVal) = {
    (if (tuple._1.startsWith(suffix)) tuple._1 else suffix + tuple._1) -> tuple._2
  }

  override def onReceive(message: Any, sender: ActorRef): Unit = {
    message match {
      case g@GetSuccess(dataKey, Some(msg)) => msg match {
        case InitMe =>
          updateLocalState(g.get(dataKey))
          stash.foreach{replicator ! _}
          stash =  Vector()
          //actors behaviors would be better, but we work with typed actor
          stateInitialized = true
          log.info("{} initialized with data: {}", ownId, data)
        case a:Any =>
          log.warning("handler for message is not implemented:{}:{}", a, a.getClass)
      }
      case NotFound(dataKey, Some(msg)) => msg match {
        case InitMe =>
          stash.foreach{replicator ! _}
          stash =  Vector()
          //actors behaviors would be better, but we work with typed actor
          stateInitialized = true
          log.info("{} initialized from the scratch", ownId)
        case a:Any =>
          log.warning("handler for message is not implemented:{}:{}", a, a.getClass)
      }
      case e@RequestEnvelope(requests) =>
        log.info("{}: received {}", ownId, e)
        checkPermission()
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

      case a:Any =>
        log.warning("unhandled  message: {}", a)
    }
  }

  private def getTokens(sender: ActorRef) = {
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
    val initTimeout:FiniteDuration = FiniteDuration(5, TimeUnit.SECONDS)// 5 minutes //we can't use standard one - it's too short. We can't use infinite - it's to long ;)
    replicator ! Get(dataKey, readLocal, Some(InitMe))
  }

  override def postStop(): Unit = {
    replicator ! Unsubscribe(dataKey, self)
    log.info("{} stopped", ownId)
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

}

/**
  * Factory for [[DistributedDSANode]] instances.
  */
object DistributedDSANode {

  def initialData(nodeDescription: DSANodeDescription, value: DSAVal) = {
    DistributedDSANodeData(
      value,
      configs = nodeDescription.attrAndConf.filter(_._1.startsWith("$")),
      attributes = nodeDescription.attrAndConf.filter(_._1.startsWith("@"))
    )
  }

  case class DistributedDSANodeData(value: DSAVal,
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
