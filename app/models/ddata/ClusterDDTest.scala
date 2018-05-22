package models.ddata

import java.util.concurrent.ThreadLocalRandom

import akka.actor.{Actor, ActorIdentity, ActorLogging, ActorRef, ActorSystem, Identify, Props, TypedActor}
import akka.cluster.Cluster
import akka.cluster.ddata.Replicator._
import akka.cluster.ddata.{ORMultiMap, _}
import com.typesafe.config.ConfigFactory
import models.RequestEnvelope
import models.Settings.Nodes.Data
import models.akka.{BrokerActors, RootNodeActor, StandardActions}
import models.akka.cluster.ClusteredDSLinkManager
import models.api.DSANode
import models.ddata.DataBot.Tick
import models.metrics.{EventDaos, NullDaos}
import models.rpc.DSAMethod.Set
import models.rpc.DSAValue.DSAVal
import models.rpc.{DSARequest, DSAValue, SetRequest, SubscribeRequest, SubscriptionPath}
import play.api.libs.json.Json

import scala.concurrent.duration._


object ClusterDDTest {


  def main(args: Array[String]): Unit = {
    if (args.isEmpty)
      startup(Seq("2551", "2552", "0"))
    else
      startup(args)
  }

  def startup(ports: Seq[String]): Unit = {
    ports.foreach { port =>
      // Override the configuration of the port
      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
        withFallback(ConfigFactory.load(
          ConfigFactory.parseString("""
            akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
            akka.remote {
              netty.tcp {
                hostname = "127.0.0.1"
                port = 0
              }
            }
            akka.cluster {
              seed-nodes = [
                "akka.tcp://DDTestClusterSystem@127.0.0.1:2551",
                "akka.tcp://DDTestClusterSystem@127.0.0.1:2552"]
              auto-down-unreachable-after = 10s
            }
            """)))

      // Create an Akka system
      val system = ActorSystem("DDTestClusterSystem", config)

      val nullDaos = EventDaos(new NullDaos.NullMemberEventDao, new NullDaos.NullDSLinkEventDao,
        new NullDaos.NullRequestEventDao, new NullDaos.NullResponseEventDao)

      val mgr = new ClusteredDSLinkManager(true, nullDaos)(system)

      ////val ba = new BrokerActors(system, mgr, nullDaos)

      val dataNode = TypedActor(system).typedActorOf(DSANode.props(None), Data)
  //    dataNode.profile = "broker/dataRoot"
     // dataNode.addChild("child1")
      StandardActions.bindDataRootActions(dataNode)

      // Create an actor that handles cluster domain events
      system.actorOf(Props[DataKeeperActor], name = "dataKeeper")

      system.actorOf(Props[ClusterDDTest], name = "pinger")

      ////system.actorOf(Props[RootNodeActor], name = "root")

      // To check printed actor's tree on startup (invokes ActorSystem's private method printTree):
      val res = new PrivateMethodExposer(system)('printTree)()
      println(res)
    }
  }

}

class ClusterDDTest extends Actor with ActorLogging {

  import context.dispatcher

  val identifyId = 888

  val pingTask = context.system.scheduler.schedule(5.seconds,
    5.seconds, self, "ping")

  override def receive: Receive = {
    case msg @ ActorIdentity(`identifyId`, Some(ref)) =>
      log.info(s">> IDENTIFY: $ref via $msg by $this")
      val r1 = SetRequest(3, "val", "dd", None)
//      val setReq = SetRequest(12, "val12", DSAValue(99))
      //val envelop = RequestEnvelope(List[r1])
      val req = SubscribeRequest(11, SubscriptionPath("/data1", 101))
      val env = RequestEnvelope(List(req))
      //RequestEnvelope(List(SetRequest(111, "/downstream/R/blah", 5))), requesters(1).ref
    case r =>
      log.info(s">> PING: $this got $r")
      context.actorSelection("../data/*") ! Identify(identifyId)
  }
}


class PrivateMethodCaller(x: AnyRef, methodName: String) {
  def apply(_args: Any*): Any = {
    val args = _args.map(_.asInstanceOf[AnyRef])

    def _parents: Stream[Class[_]] = Stream(x.getClass) #::: _parents.map(_.getSuperclass)

    val parents = _parents.takeWhile(_ != null).toList
    val methods = parents.flatMap(_.getDeclaredMethods)
    val method = methods.find(_.getName == methodName).getOrElse(throw new IllegalArgumentException("Method " + methodName + " not found"))
    method.setAccessible(true)
    method.invoke(x, args: _*)
  }
}

class PrivateMethodExposer(x: AnyRef) {
  def apply(method: scala.Symbol): PrivateMethodCaller = new PrivateMethodCaller(x, method.name)
}