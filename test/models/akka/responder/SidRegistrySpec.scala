package models.akka.responder

import models.akka.AbstractActorSpec
import models.akka.IntCounter.IntCounterState
import models.akka.responder.SidRegistry.SidRegistryState
import models.util.PartOfPersistenceBehaviorStub

import collection.mutable.{Map => MutableMap}

/**
 * SidRegistry test suite.
 */
class SidRegistrySpec extends AbstractActorSpec {

  val state = SidRegistryState(IntCounterState(1, 1), MutableMap.empty[Int, String], MutableMap.empty[String, Int])
  val registry = new SidRegistry(new PartOfPersistenceBehaviorStub, state)


  "SidRegistry" should {
    "save lookups" in {
      registry.size mustBe 0

      val tgtId1 = registry.nextTgtId
      tgtId1 mustBe 1
      registry.saveLookup("path1", tgtId1)

      val tgtId2 = registry.nextTgtId
      tgtId2 mustBe 2
      registry.saveLookup("path2", tgtId2)

      registry.size mustBe 2
    }
    "retrieve lookups" in {
      registry.lookupByPath("path1") mustBe Some(1)
      registry.lookupByPath("path2") mustBe Some(2)
      registry.lookupByPath("path3") mustBe None
    }
    "remove lookups" in {
      registry.removeLookup(2)
      registry.size mustBe 1
      registry.removeLookup(1)
      registry.size mustBe 0
    }
  }
}
