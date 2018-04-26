package models.api.typed

import scala.concurrent.duration.DurationInt

import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.util.Timeout
import models.akka.AbstractActorSpec
import models.api.typed.NodeBehavior.node
import models.api.typed.async.DSANodeAsyncAkkaImpl
import models.rpc.DSAValue.{ BooleanValue, DSAVal, longToNumericValue }

/**
 * DSANodeAsync test suite.
 */
class DSANodeAsyncSpec extends AbstractActorSpec {

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler = system.scheduler
  import system.dispatcher

  val rootState = InitState()
  val rootRef = system.spawn(node(rootState.toInternal(None)), "root")

  val root = DSANodeAsyncAkkaImpl(rootRef)

  "a node" should {
    "return valid name " in {
      whenReady(root.name) { _ mustBe "root" }
    }
    "return valid parent" in {
      whenReady(root.parent)(_ mustBe None)
      val parent = for {
        child <- root.addChild("xxx", InitState())
        parent <- child.parent
      } yield parent
      whenReady(parent)(_ mustBe Some(root))
      root.removeChild("xxx")
      Thread.sleep(200)
    }
    "handle displayName" in {
      whenReady(root.displayName) { _ mustBe None }
      root.displayName = Some("Root")
      whenReady(root.displayName) { _ mustBe Some("Root") }
    }
    "handle value" in {
      whenReady(root.value) { _ mustBe null }
      root.value = 555
      whenReady(root.value) { _ mustBe (555: DSAVal) }
    }
    "handle attributes" in {
      whenReady(root.attributes) { _ mustBe empty }
      root.attributes = Map("a" -> 1, "b" -> 2)
      whenReady(root.attributes) { _ mustBe Map("a" -> (1: DSAVal), "b" -> (2: DSAVal)) }
      root.addAttributes("c" -> 3, "b" -> true)
      whenReady(root.attributes) { _ mustBe Map("a" -> (1: DSAVal), "b" -> (true: DSAVal), "c" -> (3: DSAVal)) }
      root.removeAttribute("a")
      root.removeAttribute("c")
      whenReady(root.attributes) { _ mustBe Map("b" -> (true: DSAVal)) }
      root.clearAttributes
      whenReady(root.attributes) { _ mustBe empty }
    }
    "handle children" in {
      whenReady(root.children) { _ mustBe empty }
      val name1 = for {
        child <- root.addChild("aaa", InitState())
        name <- child.name
      } yield name
      whenReady(name1) { _ mustBe "aaa" }
      root.addChild("bbb", InitState())
      whenReady(root.children) { _.keys mustBe Set("aaa", "bbb") }
      root.removeChild("aaa")
      Thread.sleep(200)
      whenReady(root.children) { _.keys mustBe Set("bbb") }
      root.removeChildren
      Thread.sleep(200)
      whenReady(root.children) { _ mustBe empty }
    }
  }
}