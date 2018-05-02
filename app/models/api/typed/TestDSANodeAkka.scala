/*package models.api.typed

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import NodeBehavior.node
import akka.actor.typed.ActorSystem
import akka.util.Timeout

/**
 * Tests Akka-based DSANode implementation.
 */
object TestDSANodeAkka extends App {
  val system = ActorSystem(node(name="root"), "DSATree")

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler = system.scheduler
  import system.executionContext

  val root = new DSANodeAkkaImpl(system.path)
  root.displayName = "Root node"
  root.addAttributes("a" -> 1, "b" -> 2)
  root.value = 555
  root.addChild("c1")
  root.addChild("c2")
  Thread.sleep(500)

  val name = root.name
  val dname = root.displayName
  val value = root.value
  val attrs = root.attributes
  val kids = root.children

  println(s"name=$name, display=$dname, value=$value, attrs=$attrs")
  println(s"kids: $kids")

  Thread.sleep(500)

  val child = root.children.head._2
  val cname = child.name
  println("child name: " + cname)

  Thread.sleep(2000)
  Await.ready(system.terminate, 10 seconds)
}*/