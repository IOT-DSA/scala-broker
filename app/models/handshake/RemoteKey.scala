package models.handshake

import models.util.UrlBase64
import org.bouncycastle.jce.spec.ECPublicKeySpec

/**
 * Handles remote keys. The shared secret will be decrypted here.
 */
case class RemoteKey(val sharedSecret: Array[Byte])

/**
 * Factory for [[RemoteKey]] instances.
 */
object RemoteKey {

  /**
   * Generates a RemoteKey instance.
   */
  def generate(keys: LocalKeys, tempKey: String) = {
    val decoded = UrlBase64.decodeToBytes(tempKey)
    val params = keys.privateKey.getParameters
    val point1 = params.getCurve.decodePoint(decoded)
    val spec = new ECPublicKeySpec(point1, params)
    val point2 = spec.getQ().multiply(keys.privateKey.getD)
    val bi = point2.normalize.getXCoord.toBigInteger
    val sharedSecret = normalize(bi.toByteArray)
    RemoteKey(sharedSecret)
  }

  /**
   * Ensures the shared secret is always 32 bytes in length.
   */
  def normalize(data: Array[Byte]) = Array.fill(32 - data.length)(0.toByte) ++ data.takeRight(32)
}
