//package models.api.typed
//
//import akka.actor.typed._
//import scala.concurrent.Future
//import scala.concurrent.duration.DurationInt
//
//import akka.actor.typed.scaladsl.AskPattern.Askable
//import akka.actor.typed.scaladsl.Behaviors
//import akka.actor.typed.scaladsl.adapter._
//import akka.util.Timeout
//import models.akka.AbstractActorSpec
//
//class TypedRouteeSpec extends AbstractActorSpec {
//  import TypedRouteeSpec._
//  
//  val commands = scala.collection.mutable.Buffer.empty[Cmd]
//  
//  val behavior = Behaviors.receivePartial[Cmd] {
//    case (_, cmd @ CmdNon(x)) =>
//      commands.append(cmd)
//      Behaviors.same
//    case (_, CmdStr(str, ref)) =>
//      ref ! RspStr(str * 2)
//      Behaviors.same
//    case (_, CmdNum(num, Some(ref))) =>
//      ref ! RspNum(num * 2)
//      Behaviors.same
//    case (_, CmdBln(bln, ref)) =>
//      ref ! RspBln(!bln)
//      Behaviors.same
//  }  
//
//  val typedSystem = ActorSystem(behavior, "default")
//  
////  val routee = TypedRefRoutee(ActorSystem(behavior, "default"))
////
////  "TypedRefRoutee" should {
////    "not compile when used with bad parameter types" in {
////      assertDoesNotCompile("routee.send(123)")
////      assertDoesNotCompile("routee ! 123")
////    }
////    "compile when used with correct parameter types" in {
////      assertCompiles("routee.send(CmdNon(1))")
////      assertCompiles("routee ! CmdNon(1)")
////    }
////    "work for fire-and-forget messages" in {
////      routee ! CmdNon(1)
////      Thread.sleep(100)
////      commands.last mustBe CmdNon(1)
////    }
////    "work for fire-and-reply messages" in {
////      whenReady(routee.ask(CmdStr("abc"))) { x =>
////        
////      }
////      //whenReady(node ? (GetState))(_ mustBe expected)
////    }
////  }
//}
//
//object TypedRouteeSpec {
//  final case class RspStr(str: String)
//  final case class RspNum(num: Int)
//  final case class RspBln(bln: Boolean)
//
//  sealed trait Cmd
//  final case class CmdNon(x: Int) extends Cmd
//  final case class CmdStr(str: String, replyTo: ActorRef[RspStr]) extends Cmd with Replyable[RspStr]
//  final case class CmdNum(num: Int, replyTo: Option[ActorRef[RspNum]]) extends Cmd with MaybeReplyable[RspNum]
//  final case class CmdBln(num: Boolean, replyTo: ActorRef[RspBln]) extends Cmd
//}