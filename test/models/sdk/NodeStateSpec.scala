package models.sdk

import models.akka.AbstractActorSpec
import models.rpc.DSAValue._
import models.api.DSAValueType._

/**
  * NodeState test suite.
  */
class NodeStateSpec extends AbstractActorSpec {

  "NodeState.valueType" should {
    "extract $type entry from configs" in {
      stateWithConfig(ValueTypeCfg -> DSANumber).valueType mustBe DSANumber
      stateWithConfig(ValueTypeCfg -> DSABoolean).valueType mustBe DSABoolean
    }
    "return default type when config is missing" in {
      stateWithConfig().valueType mustBe DSADynamic
    }
    "fail when an invalid type is passed in configs" in {
      a[NoSuchElementException] must be thrownBy stateWithConfig(ValueTypeCfg -> "unknown").valueType
    }
  }

  "NodeState.profile" should {
    "extract $is entry from configs" in {
      stateWithConfig(ProfileCfg -> "device").profile mustBe "device"
    }
    "return default profile when config is missing" in {
      stateWithConfig().profile mustBe "node"
    }
  }

  "NodeState.displayName" should {
    "extract $name entry from configs" in {
      stateWithConfig(DisplayCfg -> "aaa").displayName mustBe Some("aaa")
      stateWithConfig().displayName mustBe empty
    }
  }

  private def stateWithConfig(cfgs: (String, DSAVal)*) = DefaultNodeState(None, None, Map.empty, cfgs.toMap, Set.empty)
}
