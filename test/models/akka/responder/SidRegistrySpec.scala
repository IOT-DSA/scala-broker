package models.akka.responder

import models.akka.AbstractActorSpec

/**
 * SidRegistry test suite.
 */
class SidRegistrySpec extends AbstractActorSpec {

  val registry = new SidRegistry

  "SidRegistry" should {
    "save lookups" in {
      registry.size mustBe 0
      registry.saveLookup("path1") mustBe 1
      registry.saveLookup("path2") mustBe 2
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