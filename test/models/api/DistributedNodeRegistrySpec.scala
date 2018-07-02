package models.api

import models.akka.AbstractActorSpec

class DistributedNodeRegistrySpec extends AbstractActorSpec{

  import DistributedNodesRegistry.parentPath

  "parent path def" should {
    "find empty name and parent for root node" in {
      parentPath("/") mustBe((None, ""))
    }

    "find name and empty parent for first level root node" in {
      parentPath("/data") mustBe((None, "data"))
    }

    "find name and parent for root node" in {
      parentPath("/data/child") mustBe((Some("/data"), "child"))
      parentPath("/data/child/grandChild") mustBe((Some("/data/child"), "grandChild"))
    }
  }

}
