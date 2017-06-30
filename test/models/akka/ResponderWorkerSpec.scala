package models.akka

import scala.concurrent.duration.DurationInt

import akka.actor.{ ActorRef, Props, actorRef2Scala }
import akka.pattern.ask
import akka.routing.{ Broadcast, ConsistentHashingPool }
import akka.testkit.TestProbe
import akka.util.Timeout
import models.{ Origin, ResponseEnvelope }
import models.rpc.DSAResponse
import models.rpc.DSAValue.{ longToNumericValue, obj }

/**
 * ResponderWorker test suite.
 */
class ResponderWorkerSpec extends AbstractActorSpec {
  import ResponderWorker._

  implicit val timeout = Timeout(10 seconds)

  private val pool = ConsistentHashingPool(3, hashMapping = {
    case AddOrigin(_, origin)   => origin
    case RemoveOrigin(origin)   => origin
    case LookupTargetId(origin) => origin
  })

  "ResponderWorker" should {
    val router = system.actorOf(pool.props(Props(new ResponderWorker("") {})))
    "add origins for targetId" in {
      router ! AddOrigin(1, Origin(testActor, 101))
      router ! AddOrigin(3, Origin(testActor, 301))
      router ! AddOrigin(3, Origin(testActor, 302))
      router ! AddOrigin(3, Origin(testActor, 303))
      router ! AddOrigin(2, Origin(testActor, 201))
      router ! AddOrigin(1, Origin(testActor, 102))
      getOrigins(router, 1) mustBe Set(Origin(testActor, 101), Origin(testActor, 102))
      getOrigins(router, 2) mustBe Set(Origin(testActor, 201))
      getOrigins(router, 3) mustBe Set(Origin(testActor, 301), Origin(testActor, 302), Origin(testActor, 303))
    }
    "lookup targetId by origin" in {
      whenReady(router ? LookupTargetId(Origin(testActor, 303))) { _ mustBe Some(3) }
      whenReady(router ? LookupTargetId(Origin(testActor, 201))) { _ mustBe Some(2) }
      whenReady(router ? LookupTargetId(Origin(testActor, 102))) { _ mustBe Some(1) }
      whenReady(router ? LookupTargetId(Origin(testActor, 404))) { _ mustBe None }
    }
    "remove origins for targetId" in {
      router ! RemoveOrigin(Origin(testActor, 101))
      getOrigins(router, 1) mustBe Set(Origin(testActor, 102))
      router ! RemoveOrigin(Origin(testActor, 303))
      getOrigins(router, 3) mustBe Set(Origin(testActor, 301), Origin(testActor, 302))
    }
    "remove all origins for targetId" in {
      router ! Broadcast(RemoveAllOrigins(3))
      getOrigins(router, 1) mustBe Set(Origin(testActor, 102))
      getOrigins(router, 2) mustBe Set(Origin(testActor, 201))
      getOrigins(router, 3) mustBe empty
    }
    "get origin count" in {
      getOriginCount(router, 1) mustBe 1
      getOriginCount(router, 2) mustBe 1
      getOriginCount(router, 3) mustBe 0
    }
    "set and get last response" in {
      router ! Broadcast(SetLastResponse(2, DSAResponse(2)))
      getLastResponse(router, 1) mustBe None
      getLastResponse(router, 2) mustBe Some(DSAResponse(2))
      getLastResponse(router, 3) mustBe None
    }
  }

  "ResponderListWorker" should {
    "deliver responses to origins" in {
      val listRouter = system.actorOf(pool.props(ResponderListWorker.props("R")))

      val upd1 = Some(List(obj("a" -> 1)))
      val upd2 = Some(List(obj("b" -> 2)))

      val requesters = (1 to 5) map (_ -> TestProbe()) toMap

      listRouter ! AddOrigin(1, Origin(requesters(4).ref, 401))
      listRouter ! AddOrigin(1, Origin(requesters(2).ref, 201))
      listRouter ! AddOrigin(2, Origin(requesters(1).ref, 101))
      listRouter ! AddOrigin(2, Origin(requesters(3).ref, 301))

      listRouter ! Broadcast(DSAResponse(rid = 1, updates = upd1))

      receiveResponses(requesters(4), 1) mustBe List(DSAResponse(rid = 401, updates = upd1))
      receiveResponses(requesters(2), 1) mustBe List(DSAResponse(rid = 201, updates = upd1))

      listRouter ! Broadcast(DSAResponse(rid = 2, updates = upd2))

      receiveResponses(requesters(1), 1) mustBe List(DSAResponse(rid = 101, updates = upd2))
      receiveResponses(requesters(3), 1) mustBe List(DSAResponse(rid = 301, updates = upd2))
    }
    "copy last responses to new origins" in {
      val listRouter = system.actorOf(pool.props(ResponderListWorker.props("R")))

      val upd1 = Some(List(obj("a" -> 1)))

      val requesters = (1 to 5) map (_ -> TestProbe()) toMap

      listRouter ! Broadcast(DSAResponse(rid = 1, updates = upd1))

      listRouter ! AddOrigin(1, Origin(requesters(1).ref, 101))
      listRouter ! AddOrigin(1, Origin(requesters(2).ref, 201))

      receiveResponses(requesters(1), 1) mustBe List(DSAResponse(rid = 101, updates = upd1))
      receiveResponses(requesters(2), 1) mustBe List(DSAResponse(rid = 201, updates = upd1))
    }
  }

  "ResponderSubscribeWorker" should {
    "deliver responses to origins" in {
      val subsRouter = system.actorOf(pool.props(ResponderSubscribeWorker.props("R")))

      val upd1 = Some(List(obj("sid" -> 1, "data" -> 111)))
      val upd2 = Some(List(obj("sid" -> 2, "data" -> 222)))
      val upd12 = Some(List(obj("sid" -> 1, "data" -> 111), obj("sid" -> 2, "data" -> 222)))

      val upd401 = Some(List(obj("sid" -> 401, "data" -> 111)))
      val upd402 = Some(List(obj("sid" -> 402, "data" -> 222)))

      val upd201 = Some(List(obj("sid" -> 201, "data" -> 111)))
      val upd202 = Some(List(obj("sid" -> 202, "data" -> 222)))

      val requesters = (1 to 5) map (_ -> TestProbe()) toMap

      subsRouter ! AddOrigin(1, Origin(requesters(4).ref, 401))
      subsRouter ! AddOrigin(1, Origin(requesters(2).ref, 201))

      subsRouter ! Broadcast(DSAResponse(rid = 0, updates = upd1))

      receiveResponses(requesters(4), 1) mustBe List(DSAResponse(rid = 0, updates = upd401))
      receiveResponses(requesters(2), 1) mustBe List(DSAResponse(rid = 0, updates = upd201))

      subsRouter ! AddOrigin(2, Origin(requesters(4).ref, 402))
      subsRouter ! AddOrigin(2, Origin(requesters(2).ref, 202))

      subsRouter ! Broadcast(DSAResponse(rid = 0, updates = upd12))

      receiveResponses(requesters(4), 2).toSet mustBe Set(
        DSAResponse(rid = 0, updates = upd401), DSAResponse(rid = 0, updates = upd402))
      receiveResponses(requesters(2), 2).toSet mustBe Set(
        DSAResponse(rid = 0, updates = upd201), DSAResponse(rid = 0, updates = upd202))
    }
    "copy last responses to new origins" in {
      val subsRouter = system.actorOf(pool.props(ResponderSubscribeWorker.props("R")))

      val upd1 = Some(List(obj("sid" -> 1, "data" -> 111)))
      val upd401 = Some(List(obj("sid" -> 401, "data" -> 111)))

      val requesters = (1 to 5) map (_ -> TestProbe()) toMap

      subsRouter ! Broadcast(DSAResponse(rid = 0, updates = upd1))

      subsRouter ! AddOrigin(1, Origin(requesters(4).ref, 401))

      receiveResponses(requesters(4), 1) mustBe List(DSAResponse(rid = 0, updates = upd401))
    }
  }

  private def getOrigins(router: ActorRef, targetId: Int) = {
    router ! Broadcast(GetOrigins(targetId))
    aggregate[Set[Origin]](_.flatMap(o => o).toSet)
  }

  private def getOriginCount(router: ActorRef, targetId: Int) = {
    router ! Broadcast(GetOriginCount(targetId))
    aggregate[Int](_.sum)
  }

  private def getLastResponse(router: ActorRef, targetId: Int) = {
    router ! Broadcast(GetLastResponse(targetId))
    aggregate[Option[DSAResponse]] { values =>
      values.tail must contain only values.head
      values.head
    }
  }

  private def receiveResponses(probe: TestProbe, count: Int) = {
    val envelopes = Iterator.continually(probe.receiveOne(1 minute).asInstanceOf[ResponseEnvelope])
    envelopes flatMap (_.responses) take count toList
  }

  private def aggregate[T](func: Seq[T] => T): T =
    func(receiveN(pool.nrOfInstances).map(_.asInstanceOf[T]))
}