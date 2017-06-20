package models.api

import org.scalatest.{ MustMatchers, WordSpecLike }
import org.scalatest.concurrent.ScalaFutures

import akka.actor.{ ActorSystem, TypedActor }
import akka.testkit.TestKit
import models.actors.AkkaRouter
import net.sf.ehcache.CacheManager
import play.api.cache.EhCacheApi

/**
 * DSA Node test suite.
 */
class DSANodeSpec extends TestKit(ActorSystem()) with WordSpecLike with MustMatchers with ScalaFutures {
  import models.rpc.DSAValue._

  val cache = new EhCacheApi(CacheManager.getInstance.addCacheIfAbsent("test"))
  val router = new AkkaRouter(cache)
  val extension = TypedActor(system)

//  "DSANode.props" should {
//    "create a new node instance" in {
//      val node = extension.typedActorOf(DSANode.props(router, cache, None), "bar1")
//      extension.stop(node)
//    }
//    "support `path`, `name`, `parent`" in {
//      val node = extension.typedActorOf(DSANode.props(router, cache, None), "bar2")
//      node.parent mustBe None
//      node.name mustBe "bar2"
//      node.path mustBe "/bar2"
//    }
//  }
//
//  "DSANode" should {
//    val node = extension.typedActorOf(DSANode.props(router, cache, None), "bar3")
//    "support `value`" in {
//      node.value = 5: DSAVal
//      whenReady(node.value) { _ mustBe (5: DSAVal) }
//    }
//    "support `profile`" in {
//      node.profile = "data"
//      node.profile mustBe "data"
//    }
//    "support `displayName`" in {
//      node.displayName = "Data"
//      whenReady(node.displayName) { _ mustBe "Data" }
//    }
//    "support `attributes`" in {
//      node.addAttributes("@a" -> 1, "@b" -> true, "@c" -> "hello")
//      whenReady(node.attributes) { _.size mustBe 3 }
//      whenReady(node.attribute("@a")) { _ mustBe Some(1: DSAVal) }
//      whenReady(node.attribute("@b")) { _ mustBe Some(true: DSAVal) }
//      whenReady(node.attribute("@c")) { _ mustBe Some("hello": DSAVal) }
//    }
//    "support `configs`" in {
//      node.addConfigs("$a" -> 1, "$b" -> true, "$c" -> "hello")
//      whenReady(node.configs) { _.size mustBe 3 }
//      whenReady(node.config("$a")) { _ mustBe Some(1: DSAVal) }
//      whenReady(node.config("$b")) { _ mustBe Some(true: DSAVal) }
//      whenReady(node.config("$c")) { _ mustBe Some("hello": DSAVal) }
//    }
//    "support `children`" in {
//      whenReady(node.addChild("child")) { child =>
//        child.name mustBe "child"
//        child.parent.map(_.name) mustBe Some("bar3")
//        child.path mustBe "/bar3/child"
//      }
//    }
//  }
}