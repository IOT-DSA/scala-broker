package models.api.typed

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import MgmtCommand._
import NodeBehavior.node
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.util.Timeout

/**
 * Tests typed node actors.
 */
object TestActors extends App {

  val rootState = DSANodeState(None, "root", null, 0, Map.empty)
  val system = ActorSystem(node(rootState), "DSATree")

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler = system.scheduler
  import system.executionContext
  val ignore = system.deadLetters

  system ! SetDisplayName("Root Node")
  system ! SetValue(5)
  system ! PutAttribute("abc", 123)
  system ! AddChild(DSANodeState(Some(system), "aaa", null, 0, Map.empty), ignore)
  system ! AddChild(DSANodeState(Some(system), "bbb", "Bbb", 1, Map("a" -> 1)), ignore)
  system ! AddChild(DSANodeState(Some(system), "ccc", null, "x", Map.empty), ignore)

  for {
    state <- system ? (GetState(_))
    children <- system ? (GetChildren(_))
    first <- children.head ? (GetState(_))
    second <- children.tail.head ? (GetState(_))
    third <- children.tail.tail.head ? (GetState(_))
  } {
    println("state: " + state)
    println("first: " + first)
    println("second: " + second)
    println("third: " + third)
  }
  Thread.sleep(500)

  system ! RemoveChild("bbb")
  Thread.sleep(1000)

  for {
    children <- system ? (GetChildren(_))
  } {
    println("children: " + children)
  }

  Thread.sleep(1000)

  Await.ready(system.terminate, 10 seconds)
}