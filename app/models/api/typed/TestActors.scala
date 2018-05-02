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

  val system = ActorSystem(node(name="root"), "DSATree")

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler = system.scheduler
  import system.executionContext
  val ignore = system.deadLetters

  system ! SetDisplayName("RootNode111")
  system ! SetValue(5)
//  system ! PutAttribute("abc", 123)
  system ! SetAttributes(Map("atr1" -> 777, "atr2" -> 666))
  system ! AddChild(DSANodeState(Some(system.path), "aaa", "Aaa", 10, Map.empty, List.empty), ignore)
  system ! AddChild(DSANodeState(Some(system.path), "bbb", "Bbb", 1, Map("a" -> 1), List.empty), ignore)
  system ! AddChild(DSANodeState(Some(system.path), "ccc", "Ccc", "x", Map.empty, List.empty), ignore)

//  for (entityId <- entityIds)
//    system ! RecoverBy(entityId)

  Thread.sleep(1000)

  for {
    state <- system ? (GetState)
    children <- system ? (GetChildren)

    first <- children.head ? (GetState)
    second <- children.tail.head ? (GetState)
    third <- children.tail.tail.head ? (GetState)
  } {
    println("state: " + state)
    println("children: " + children)
    println("first: " + first)
    println("second: " + second)
    println("third: " + third)
  }
  Thread.sleep(500)

  system ! RemoveChild("bbb")
  system ! RemoveAttribute("atr1")
  Thread.sleep(1000)

  for {
    state <- system ? (GetState)
    children <- system ? (GetChildren)
  } {
    println("state: " + state)
    println("children: " + children)
  }

  Thread.sleep(1000)

  Await.ready(system.terminate, 10 seconds)
}