package models.handshake

import org.scalatestplus.play.PlaySpec

/**
 * Test suite for LocalKeys.
 */
class LocalKeysSpec extends PlaySpec {

  "serialized-deserialized keys" should {
    "retain equality to the originals" in {
      val keys = LocalKeys.generate
      val newKeys = LocalKeys.deserialize(keys.serialize)

      keys.hashCode mustBe newKeys.hashCode
      keys mustEqual newKeys
    }
  }

}