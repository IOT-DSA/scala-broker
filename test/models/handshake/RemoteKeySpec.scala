package models.handshake

import org.scalatestplus.play.PlaySpec

/**
 * Test suite for RemoteKey.
 */
class RemoteKeySpec extends PlaySpec {

  "normalize" should {
    "left-pad arrays shorter than 32" in {
      val data = Array[Byte](1, 3, 5, 7)
      RemoteKey.normalize(data) mustBe Array.fill(28)(0.toByte) ++ data
    }
    "left-truncate arrays longer than 32" in {
      val data = (1 to 40).toArray map (_.toByte)
      RemoteKey.normalize(data) mustBe (9 to 40).toArray.map(_.toByte)
    }
    "leave arrays of size 32 untouched" in {
      val data = (1 to 32).toArray map (_.toByte)
      RemoteKey.normalize(data) mustBe data
    }
  }

  "generate" should {
    "generate a remote key from local keys and temp key" in {
      // Client gives its key to the server
      val localKeys = LocalKeys.generate

      // Server generates a temporary local key
      val point2 = localKeys.publicKey.getQ
      val tempLocalKey = LocalKeys.generate
      val point = point2.multiply(tempLocalKey.privateKey.getD) // Q2
      val bi = point.normalize.getXCoord.toBigInteger
      val sharedSecret = RemoteKey.normalize(bi.toByteArray())

      // Client receives temp key
      val tempKey = tempLocalKey.encodedPublicKey
      val remoteKey = RemoteKey.generate(localKeys, tempKey)
      sharedSecret mustBe remoteKey.sharedSecret
    }
  }
}