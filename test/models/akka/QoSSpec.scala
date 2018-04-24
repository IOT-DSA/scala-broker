//package models.akka
//
//import akka.actor.{Actor, Props}
//import akka.routing.{ActorRefRoutee, NoRoutee}
//import akka.testkit.TestProbe
//import models.ResponseEnvelope
//import models.akka.RequesterBehaviorSpec.Requester
//import models.akka.local.LocalDSLinkManager
//import models.akka.responder.SimpleResponderBehaviorSpec
//import models.metrics.EventDaos
//import models.rpc.{DSAResponse, StreamState}
//
//class QoSSpec extends AbstractActorSpec {
//
//  val ci = ConnectionInfo("", "", false, true)
//  val dslinkMgr = new LocalDSLinkManager(nullDaos)
//
//  class DownstreamActor extends Actor {
//
//    def receive = { case msg => downstreamProbe.ref ! msg }
//  }
//
//  val downstreamActor = system.actorOf(Props(new DownstreamActor), "downstream")
//  val requester = system.actorOf(Props(new Requester(dslinkMgr, ActorRefRoutee(downstreamActor), nullDaos)), "requester")
//  val ws = TestProbe()
//  requester.tell(Messages.ConnectEndpoint(ws.ref, ci), ws.ref)
//
//  "QoS system should" should {
//    "keep all non subscription messages if DSLink disconnected"
//
//      requester ! ResponseEnvelope(
//        Seq(
//          DSAResponse(
//            rid = 1,
//            stream = Some(StreamState.Open),
//
//          )
//        )
//      )
//
//
//  }
//}
//
//
