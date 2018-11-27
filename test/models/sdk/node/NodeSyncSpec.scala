package models.sdk.node

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.stream.scaladsl.Sink
import akka.stream.typed.scaladsl.ActorMaterializer
import akka.util.Timeout
import models.akka.AbstractActorSpec
import models.api.DSAValueType
import models.rpc.DSAValue.{DSAVal, obj}
import models.sdk.Implicits._
import models.sdk.NodeRef
import models.sdk.node.NodeCommand._
import models.sdk.node.NodeEvent._
import models.sdk.node.sync.NodeSyncAkkaImpl

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * NodeSync API test suite.
  */
class NodeSyncSpec extends AbstractActorSpec {

  import system.dispatcher

  implicit val timeout: Timeout = 5 seconds
  implicit val scheduler = system.scheduler
  implicit val materializer = ActorMaterializer()(system.toTyped)

  // prepare root actor
  val rootRef = system.spawn(NodeBehavior.node(None), "root")
  rootRef ! SetValue(Some(555))
  rootRef ! PutAttribute("abc", 123)
  rootRef ! SetProfile("rootNode")

  val root = NodeSyncAkkaImpl(rootRef)
  val child = NodeSyncAkkaImpl(Await.result((rootRef ? (AddChild("childA", _))): Future[NodeRef], Duration.Inf))

  "async node API" should {
    "return valid status" in {
      val statuses = for {
        status1 <- rootRef ? GetStatus
        status2 = root.status
      } yield (status1, status2)
      whenReady(statuses) {
        case (s1, s2) => s1 mustBe s2
      }
    }
    "manage value" in {
      root.value mustBe Some(555: DSAVal)
      root.value = Some(333)
      root.value mustBe Some(333: DSAVal)
    }
    "manage display name" in {
      root.displayName mustBe None
      root.displayName = "Root"
      root.displayName.value mustBe "Root"
    }
    "manage value type" in {
      root.valueType mustBe DSAValueType.DSADynamic
      root.valueType = DSAValueType.DSANumber
      root.valueType mustBe DSAValueType.DSANumber
    }
    "manage profile" in {
      root.profile mustBe "rootNode"
      root.profile = "static"
      root.profile mustBe "static"
    }
    "manage actions" in {
      child.status.invokable mustBe false
      child.action = NodeAction(ctx => {
        val a = ctx.as[Int]("a")
        val b = ctx.as[Int]("b")
        val result = (a + b): DSAVal
        Future(ActionResult(result))
      }, Param("a", DSAValueType.DSANumber), Param("b", DSAValueType.DSANumber))
      child.action.value.params mustBe Seq(Param("a", DSAValueType.DSANumber), Param("b", DSAValueType.DSANumber))
      whenReady(child.invoke(Map("a" -> 3, "b" -> 5))) {
        _.value mustBe (8: DSAVal)
      }
    }
    "manage attributes" in {
      root.attributes mustBe Map("@abc" -> (123: DSAVal))
      root.putAttribute("@xyz", true)
      root.attributes mustBe Map("@abc" -> (123: DSAVal), "@xyz" -> (true: DSAVal))
      root.removeAttribute("@abc")
      root.attributes mustBe Map("@xyz" -> (true: DSAVal))
      root.clearAttributes()
      root.attributes mustBe empty
    }
    "manage configs" in {
      val preset = obj("$is" -> "static", "$name" -> "Root", "$type" -> DSAValueType.DSANumber).value
      root.configs mustBe preset
      root.putConfig("xyz", true)
      root.configs mustBe preset ++ obj("$xyz" -> true).value
      root.removeConfig("$xyz")
      root.configs mustBe preset
      root.clearConfigs()
      root.configs mustBe empty
    }
    "manage children" in {
      root.children.keys mustBe Set("childA")
      root.addChild("childB").status.name mustBe "childB"
      root.children.keys mustBe Set("childA", "childB")
      root.removeChild("childB")
      Thread.sleep(300)
      root.children.keys mustBe Set("childA")
      root.removeChildren()
      Thread.sleep(300)
      root.children.keys mustBe empty
    }
    "manage value listeners" in {
      val events = root.valueEvents.take(4).runWith(Sink.seq)
      val values: Seq[Option[DSAVal]] = (1 to 4) map (v => Some(v: DSAVal))
      values foreach (v => root.value = v)
      whenReady(events)(_ mustBe values.map(v => ValueChanged(v)))
    }
    "manage attribute listeners" in {
      val events = root.attributeEvents.take(4).runWith(Sink.seq)
      root.putAttribute("aaa", 1)
      root.removeAttribute("aaa")
      root.attributes = Map("x" -> 1, "y" -> 2)
      root.clearAttributes()
      whenReady(events) {
        _ mustBe Seq(AttributeAdded("@aaa", 1), AttributeRemoved("@aaa"),
          AttributesChanged(Map("@x" -> 1, "@y" -> 2)), AttributesChanged(Map.empty))
      }
    }
    "manage config listeners" in {
      val events = root.configEvents.take(4).runWith(Sink.seq)
      root.putConfig("aaa", 1)
      root.removeConfig("aaa")
      root.configs = Map("x" -> 1, "y" -> 2)
      root.clearConfigs()
      whenReady(events) {
        _ mustBe Seq(ConfigAdded("$aaa", 1), ConfigRemoved("$aaa"),
          ConfigsChanged(Map("$x" -> 1, "$y" -> 2)), ConfigsChanged(Map.empty))
      }
    }
    "manage child listeners" in {
      val events = root.childEvents.take(4).runWith(Sink.seq)
      root.addChild("childC")
      root.addChild("childD")
      root.removeChild("childD")
      root.removeChildren()
      whenReady(events) {
        _ mustBe Seq(ChildAdded("childC"), ChildAdded("childD"), ChildRemoved("childD"), ChildrenRemoved)
      }
    }
  }
}
