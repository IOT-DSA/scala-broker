package models.api.typed

import scala.concurrent.duration.DurationInt

import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.util.Timeout
import models.akka.AbstractActorSpec
import models.api.typed.NodeBehavior.node
import models.api.typed.sync.DSANodeAkkaImpl
import models.rpc.DSAValue.{ BooleanValue, DSAVal, longToNumericValue }

/**
 * DSANode test suite.
 */
class DSANodeSpec extends AbstractActorSpec {
  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler = system.scheduler
  import system.dispatcher

  val rootState = InitState()
  val rootRef = system.spawn(node(rootState.toInternal(None)), "root")

  val root = DSANodeAkkaImpl(rootRef)

  "a node" should {
    "return valid name " in {
      root.name mustBe "root"
    }
    "return valid parent" in {
      root.parent mustBe None
      root.addChild("xxx", InitState()).parent mustBe Some(root)
      root.removeChild("xxx")
    }
    "handle displayName" in {
      root.displayName mustBe None
      root.displayName = Some("Root")
      root.displayName mustBe Some("Root")
    }
    "handle value" in {
      root.value mustBe null
      root.value = 555
      root.value mustBe (555: DSAVal)
    }
    "handle attributes" in {
      root.attributes mustBe empty
      root.attributes = Map("a" -> 1, "b" -> 2)
      root.attributes mustBe Map("a" -> (1: DSAVal), "b" -> (2: DSAVal))
      root.addAttributes("c" -> 3, "b" -> true)
      root.attributes mustBe Map("a" -> (1: DSAVal), "b" -> (true: DSAVal), "c" -> (3: DSAVal))
      root.removeAttribute("a")
      root.removeAttribute("c")
      root.attributes mustBe Map("b" -> (true: DSAVal))
      root.clearAttributes
      root.attributes mustBe empty
    }
    "handle children" in {
      root.children mustBe empty
      root.addChild("aaa", InitState()).name mustBe "aaa"
      root.addChild("bbb", InitState()).name mustBe "bbb"
      root.children.keys mustBe Set("aaa", "bbb")
      root.removeChild("aaa")
      Thread.sleep(200)
      root.children.keys mustBe Set("bbb")
      root.removeChildren
      Thread.sleep(200)
      root.children mustBe empty
    }
  }
}