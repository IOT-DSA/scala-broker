package models.sdk.node

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.stream.scaladsl.Sink
import akka.stream.typed.scaladsl.ActorMaterializer
import akka.util.Timeout
import models.akka.AbstractActorSpec
import models.api.DSAValueType
import models.rpc.DSAValue._
import models.sdk.Implicits._
import models.sdk.NodeRef
import models.sdk.node.NodeCommand._
import models.sdk.node.NodeEvent._
import models.sdk.node.async.NodeAsyncAkkaImpl

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * NodeAsync API test suite.
  */
class NodeAsyncSpec extends AbstractActorSpec {

  import system.dispatcher

  implicit val timeout: Timeout = 5 seconds
  implicit val scheduler = system.scheduler
  implicit val materializer = ActorMaterializer()(system.toTyped)

  // prepare root actor
  val rootRef = system.spawn(NodeBehavior.node(None), "root")
  rootRef ! SetValue(Some(555))
  rootRef ! PutAttribute("abc", 123)
  rootRef ! SetProfile("rootNode")

  val root = NodeAsyncAkkaImpl(rootRef)
  val child = NodeAsyncAkkaImpl(Await.result((rootRef ? (AddChild("childA", _))): Future[NodeRef], Duration.Inf))

  "async node API" should {
    "return valid status" in {
      val statuses = for {
        status1 <- rootRef ? GetStatus
        status2 <- root.status
      } yield (status1, status2)
      whenReady(statuses) {
        case (s1, s2) => s1 mustBe s2
      }
    }
    "manage value" in {
      whenReady(root.value)(_ mustBe Some(555: DSAVal))
      root.value = Some(333)
      whenReady(root.value)(_ mustBe Some(333: DSAVal))
    }
    "manage display name" in {
      whenReady(root.displayName)(_ mustBe None)
      root.displayName = "Root"
      whenReady(root.displayName)(_.value mustBe "Root")
    }
    "manage value type" in {
      whenReady(root.valueType)(_ mustBe DSAValueType.DSADynamic)
      root.valueType = DSAValueType.DSANumber
      whenReady(root.valueType)(_ mustBe DSAValueType.DSANumber)
    }
    "manage profile" in {
      whenReady(root.profile)(_ mustBe "rootNode")
      root.profile = "static"
      whenReady(root.profile)(_ mustBe "static")
    }
    "manage actions" in {
      whenReady(child.status.map(_.invokable))(_ mustBe false)
      child.action = NodeAction(ctx => {
        val a = ctx.as[Int]("a")
        val b = ctx.as[Int]("b")
        val result = (a + b): DSAVal
        Future(ActionResult(result))
      }, Param("a", DSAValueType.DSANumber), Param("b", DSAValueType.DSANumber))
      whenReady(child.action) {
        _.value.params mustBe Seq(Param("a", DSAValueType.DSANumber), Param("b", DSAValueType.DSANumber))
      }
      whenReady(child.invoke(Map("a" -> 3, "b" -> 5))) {
        _.value mustBe (8: DSAVal)
      }
    }
    "manage attributes" in {
      whenReady(root.attributes)(_ mustBe Map("@abc" -> (123: DSAVal)))
      root.putAttribute("@xyz", true)
      whenReady(root.attributes)(_ mustBe Map("@abc" -> (123: DSAVal), "@xyz" -> (true: DSAVal)))
      root.removeAttribute("@abc")
      whenReady(root.attributes)(_ mustBe Map("@xyz" -> (true: DSAVal)))
      root.clearAttributes()
      whenReady(root.attributes)(_ mustBe empty)
    }
    "manage configs" in {
      val preset = obj("$is" -> "static", "$name" -> "Root", "$type" -> DSAValueType.DSANumber).value
      whenReady(root.configs)(_ mustBe preset)
      root.putConfig("xyz", true)
      whenReady(root.configs)(_ mustBe preset ++ obj("$xyz" -> true).value)
      root.removeConfig("$xyz")
      whenReady(root.configs)(_ mustBe preset)
      root.clearConfigs()
      whenReady(root.configs)(_ mustBe empty)
    }
    "manage children" in {
      whenReady(root.children)(_.keys mustBe Set("childA"))
      val fStatus = for {
        childB <- root.addChild("childB")
        status <- childB.status
      } yield status
      whenReady(fStatus)(_.name mustBe "childB")
      whenReady(root.children)(_.keys mustBe Set("childA", "childB"))
      root.removeChild("childB")
      Thread.sleep(300)
      whenReady(root.children)(_.keys mustBe Set("childA"))
      root.removeChildren()
      Thread.sleep(300)
      whenReady(root.children)(_.keys mustBe empty)
    }
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
