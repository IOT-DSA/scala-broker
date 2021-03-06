package models.akka.responder

import akka.routing.ActorSelectionRoutee
import akka.testkit.TestProbe
import models.Origin
import models.akka.AbstractActorSpec
import models.akka.IntCounter.IntCounterState
import models.util.PartOfPersistenceBehaviorStub
import collection.mutable.{Map => MutableMap}

/**
 * RidRegistry test suite.
 */
class RidRegistrySpec extends AbstractActorSpec {
  import RidRegistry._
  import models.rpc.DSAMethod._

  val Seq(a1, a2, a3) = (1 to 3) map (_ => ActorSelectionRoutee(system.actorSelection(TestProbe().ref.path)))
  def state = RidRegistryState(IntCounterState(1, 1), MutableMap.empty[Int, LookupRecord],
    MutableMap.empty[Origin, LookupRecord], MutableMap.empty[String, LookupRecord])
  val registry = new RidRegistry(new PartOfPersistenceBehaviorStub, state)

  "RidRegistry" should {
    "save/retrieve/remove LIST lookups" in {
      registry.targetIdCount mustBe 0
      registry.originCount mustBe 0
      registry.pathCount mustBe 0

      val tgtId1 = registry.nextTgtId
      registry.saveListLookup("path1", tgtId1)
      tgtId1 mustBe 1
      val record = LookupRecord(List, 1, None, Some("path1"))
      registry.lookupByTargetId(1) mustBe Some(record)
      registry.lookupByPath("path1") mustBe Some(record)
      registry.targetIdCount mustBe 1
      registry.originCount mustBe 0
      registry.pathCount mustBe 1

      registry.removeLookup(record)
      registry.lookupByTargetId(1) mustBe None
      registry.lookupByPath("path1") mustBe None
      registry.targetIdCount mustBe 0
      registry.originCount mustBe 0
      registry.pathCount mustBe 0
    }
    "save/retrieve/remove SET lookups" in {
      val tgtId2 = registry.nextTgtId
      registry.savePassthroughLookup(Set, Origin(a1, 101), tgtId2)
      tgtId2 mustBe 2
      val record = LookupRecord(Set, 2, Some(Origin(a1, 101)), None)
      registry.lookupByTargetId(2) mustBe Some(record)
      registry.lookupByOrigin(Origin(a1, 101)) mustBe Some(record)
      registry.targetIdCount mustBe 1
      registry.originCount mustBe 1
      registry.pathCount mustBe 0

      registry.removeLookup(record)
      registry.lookupByTargetId(2) mustBe None
      registry.lookupByOrigin(Origin(a1, 101)) mustBe None
      registry.targetIdCount mustBe 0
      registry.originCount mustBe 0
      registry.pathCount mustBe 0
    }
    "save/retrieve/remove INVOKE lookups" in {
      val tgtId3 = registry.nextTgtId
      registry.savePassthroughLookup(Invoke, Origin(a2, 201), tgtId3)
      tgtId3 mustBe 3
      val record = LookupRecord(Invoke, 3, Some(Origin(a2, 201)), None)
      registry.lookupByTargetId(3) mustBe Some(record)
      registry.lookupByOrigin(Origin(a2, 201)) mustBe Some(record)
      registry.targetIdCount mustBe 1
      registry.originCount mustBe 1
      registry.pathCount mustBe 0

      registry.removeLookup(record)
      registry.lookupByTargetId(3) mustBe None
      registry.lookupByOrigin(Origin(a2, 201)) mustBe None
      registry.targetIdCount mustBe 0
      registry.originCount mustBe 0
      registry.pathCount mustBe 0
    }
    "save/retrieve/remove REMOVE lookups" in {
      val tgtId4 = registry.nextTgtId
      registry.savePassthroughLookup(Remove, Origin(a3, 301), tgtId4)
      tgtId4 mustBe 4
      val record = LookupRecord(Remove, 4, Some(Origin(a3, 301)), None)
      registry.lookupByTargetId(4) mustBe Some(record)
      registry.lookupByOrigin(Origin(a3, 301)) mustBe Some(record)
      registry.targetIdCount mustBe 1
      registry.originCount mustBe 1
      registry.pathCount mustBe 0

      registry.removeLookup(record)
      registry.lookupByTargetId(4) mustBe None
      registry.lookupByOrigin(Origin(a3, 301)) mustBe None
      registry.targetIdCount mustBe 0
      registry.originCount mustBe 0
      registry.pathCount mustBe 0
    }
    "save/retrieve/remove SUBSCRIBE lookups" in {
      val tgtId5 = registry.nextTgtId
      registry.saveSubscribeLookup(Origin(a1, 102), tgtId5)
      tgtId5 mustBe 5
      val record = LookupRecord(Subscribe, 5, Some(Origin(a1, 102)), None)
      registry.lookupByTargetId(5) mustBe Some(record)
      registry.lookupByOrigin(Origin(a1, 102)) mustBe Some(record)
      registry.targetIdCount mustBe 1
      registry.originCount mustBe 1
      registry.pathCount mustBe 0

      registry.removeLookup(record)
      registry.lookupByTargetId(5) mustBe None
      registry.lookupByOrigin(Origin(a1, 102)) mustBe None
      registry.targetIdCount mustBe 0
      registry.originCount mustBe 0
      registry.pathCount mustBe 0
    }
    "save/retrieve/remove UNSUBSCRIBE lookups" in {
      val tgtId6 = registry.nextTgtId
      registry.saveUnsubscribeLookup(Origin(a2, 202), tgtId6)
      tgtId6 mustBe 6
      val record = LookupRecord(Unsubscribe, 6, Some(Origin(a2, 202)), None)
      registry.lookupByTargetId(6) mustBe Some(record)
      registry.lookupByOrigin(Origin(a2, 202)) mustBe Some(record)
      registry.targetIdCount mustBe 1
      registry.originCount mustBe 1
      registry.pathCount mustBe 0

      registry.removeLookup(record)
      registry.lookupByTargetId(6) mustBe None
      registry.lookupByOrigin(Origin(a2, 202)) mustBe None
      registry.targetIdCount mustBe 0
      registry.originCount mustBe 0
      registry.pathCount mustBe 0
    }
    "fail to save invalid passthrough request" in {
      a[IllegalArgumentException] must be thrownBy registry.savePassthroughLookup(List, Origin(a1, 111), registry.nextTgtId)
      a[IllegalArgumentException] must be thrownBy registry.savePassthroughLookup(Subscribe, Origin(a1, 111), registry.nextTgtId)
      a[IllegalArgumentException] must be thrownBy registry.savePassthroughLookup(Unsubscribe, Origin(a1, 111), registry.nextTgtId)
    }
  }
}
