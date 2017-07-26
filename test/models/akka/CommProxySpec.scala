package models.akka

import scala.concurrent.duration.DurationInt

import com.typesafe.config.ConfigFactory

import akka.actor.{ Actor, ActorRef, ActorSystem, Props }
import akka.cluster.pubsub.{ DistributedPubSub, DistributedPubSubMediator }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import akka.testkit.{ TestActors, TestKit }
import akka.util.Timeout

/**
 * CommProxy test suite.
 */
class CommProxySpec extends AbstractActorSpec {
  import CommProxySpec._
  
  val system2 = {
    val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=2551").withFallback(ConfigFactory.load("backend.conf"))
    val systemName = config.getString("play.akka.actor-system")
    ActorSystem(systemName, config.resolve)
  }
  
  override def afterAll = {
    super.afterAll
    TestKit.shutdownActorSystem(system2)
  }
  
  implicit val timeout = Timeout(5 seconds)

  "ActorRefProxy" should {
    "implement fire-and-forget" in {
      val proxy = new ActorRefProxy(testActor)
      proxy ! "MSG"
      expectMsg("MSG")
    }
    "impliement request-response" in {
      val proxy = new ActorRefProxy(system.actorOf(TestActors.echoActorProps))
      whenReady(proxy ?[String] "MSG") {_ mustBe "MSG" }
    }
  }
  
  "ActorPathProxy" should {
    "implement fire-and-forget" in {
      val proxy = new ActorPathProxy(testActor.path.toStringWithoutAddress)
      proxy ! "MSG"
      expectMsg("MSG")
    }
    "impliement request-response" in {
      val proxy = new ActorPathProxy(system.actorOf(TestActors.echoActorProps).path.toStringWithoutAddress)
      whenReady(proxy ?[String] "MSG") {_ mustBe "MSG" }
    }
  }
  
  "ShardedActorProxy" should {
    val region = ClusterSharding(system2).start("Entity", Props(new EntityActor(testActor)), 
          ClusterShardingSettings(system2), extractEntityId, extractShardId)
    "implement fire-and-forget" in {
      val proxy = new ShardedActorProxy(region, "123")
      proxy ! "MSG"
      expectMsgAllOf(("123", "MSG"), ("123", "MSG"))
    }
    "impliement request-response" in {
      val proxy = new ShardedActorProxy(region, "456")
      whenReady(proxy ?[Tuple2[String, String]] "MSG") {_ mustBe Tuple2("456", "MSG") }
      expectMsg(Tuple2("456", "MSG"))
    }
  }
  
  "PubSubProxy" should {
    val mediator = DistributedPubSub(system2).mediator
    "implement fire-and-forget" in {
      mediator ! DistributedPubSubMediator.Put(testActor)
      val proxy = new PubSubProxy(mediator, testActor.path.toStringWithoutAddress)
      proxy ! "MSG"
      expectMsg("MSG")
    }
    "impliement request-response" in {
      val echo = system.actorOf(TestActors.echoActorProps)
      mediator ! DistributedPubSubMediator.Put(echo)
      val proxy = new PubSubProxy(mediator, echo.path.toStringWithoutAddress)
      whenReady(proxy ?[String] "MSG") {_ mustBe "MSG" }
    }
  }  
}

/**
 * Common defininition for [[CommProxySpec]].
 */
object CommProxySpec {
  
  class EntityActor(ref: ActorRef) extends Actor {
    val id = self.path.name
    def receive = {
      case msg => ref ! Tuple2(id, msg); sender ! Tuple2(id, msg)
    }
  }
  
  val shardCount = 10
  
  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(id, payload) => (id, payload)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(id, _) => (math.abs(id.hashCode) % shardCount).toString
  } 
}