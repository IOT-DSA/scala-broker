package models.api.typed

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import NodeBehavior.node
import akka.actor.typed.ActorSystem
import akka.util.Timeout

/**
 * Tests Akka-based DSANodeAsync implementation.
 */
object TestDSANodeAsyncAkka extends App {
  val rootState = DSANodeState(None, "root", null, 0, Map.empty)
  val system = ActorSystem(node(rootState), "DSATree")

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler = system.scheduler
  import system.executionContext

  val root = new DSANodeAsyncAkkaImpl(system)
  root.displayName = "Root node"
  root.addAttributes("a" -> 1, "b" -> 2)
  root.value = 555
  root.addChild("c1")
  root.addChild("c2")
  Thread.sleep(500)

  for {
    name <- root.name
    dname <- root.displayName
    value <- root.value
    attrs <- root.attributes
    kids <- root.children
  } {
    println(s"name=$name, display=$dname, value=$value, attrs=$attrs")
    println(s"kids: $kids")
  }

  Thread.sleep(500)

  val child = root.children.map(_.head._2)
  for {
    n <- child
    name <- n.name
  } {
    println("child name: " + name)
  }

  Thread.sleep(2000)
  Await.ready(system.terminate, 10 seconds)
}