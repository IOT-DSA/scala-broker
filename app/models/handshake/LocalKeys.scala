package models.handshake

import java.io.File
import java.math.BigInteger
import java.nio.file.{ Files, Paths }
import java.security.spec.ECGenParameterSpec
import java.util.Arrays

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.{ ECDomainParameters, ECPrivateKeyParameters, ECPublicKeyParameters }
import org.bouncycastle.jcajce.provider.asymmetric.ec.{ BCECPrivateKey, BCECPublicKey, KeyPairGeneratorSpi }
import org.bouncycastle.jcajce.provider.digest.SHA256
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveSpec

import com.google.common.io.ByteStreams

import models.util.UrlBase64

/**
 * Manages a local key ring with a key pair.
 */
case class LocalKeys(privateKey: BCECPrivateKey, publicKey: BCECPublicKey) {
  import LocalKeys._

  /**
   * Encoded Q of the public key.
   */
  val encodedPublicQ = publicKey.getQ.getEncoded(false)

  /**
   * The public key first sent through a SHA256 hash which is then encoded into base64.
   */
  val encodedHashedPublicKey = {
    val sha = new SHA256.Digest
    UrlBase64.encodeBytes(sha.digest(encodedPublicQ))
  }

  /**
   * Encodes the Q of the public key. The encoding is uncompressed.
   */
  val encodedPublicKey = UrlBase64.encodeBytes(encodedPublicQ)

  /**
   * Serializes the public and private keys in a standard form. The format is
   * base64 encoded D and base64 encoded Q separated by a space.
   */
  def serialize = {
    val encodedD = UrlBase64.encodeBytes(privateKey.getD.toByteArray)
    val encodedQ = UrlBase64.encodeBytes(encodedPublicQ)

    encodedD + " " + encodedQ
  }

  /**
   * Computes a hash code value for this object.
   */
  override def hashCode = 31 * privateKey.getD.hashCode + Arrays.hashCode(encodedPublicQ)

  /**
   * Checks whether the keys are the same.
   */
  override def equals(o: Any) = o match {
    case other: LocalKeys =>
      privateKey.getD == other.privateKey.getD && encodedPublicQ.deep == other.encodedPublicQ.deep
    case _ => false
  }
}

/**
 * Factory for [[LocalKeys]] instances.
 */
object LocalKeys {

  /* The elliptic-curve algorithm used. */
  val EC_CURVE = "SECP256R1"

  /**
   * The parameters of the curve type.
   */
  val EC_Params = {
    val ecp = SECNamedCurves.getByName(EC_CURVE)
    new ECDomainParameters(ecp.getCurve, ecp.getG, ecp.getN, ecp.getH, ecp.getSeed)
  }

  /**
   * The parameter spec of the algorithm.
   */
  val EC_ParamSpec = new ECNamedCurveSpec(EC_CURVE, EC_Params.getCurve, EC_Params.getG, EC_Params.getN,
    EC_Params.getH, EC_Params.getSeed)

  /**
   * Generates a key pair as necessary to perform a handshake.
   */
  def generate = {
    val gen = new KeyPairGeneratorSpi.ECDH
    val spec = new ECGenParameterSpec(EC_CURVE)
    gen.initialize(spec)

    val pair = gen.generateKeyPair
    val privateKey = pair.getPrivate.asInstanceOf[BCECPrivateKey]
    val publicKey = pair.getPublic.asInstanceOf[BCECPublicKey]

    LocalKeys(privateKey, publicKey)
  }

  /**
    * Generates "saled" shared secret
    *
    * @param salt
    * @param sharedSecret
    * @return
    */
  def saltSharedSecret(salt: Array[Byte], sharedSecret: Array[Byte]) = {
    // TODO make more scala-like
    val bytes = Array.ofDim[Byte](salt.length + sharedSecret.length)
    System.arraycopy(salt, 0, bytes, 0, salt.length)
    System.arraycopy(sharedSecret, 0, bytes, salt.length, sharedSecret.length)

    val sha = new SHA256.Digest
    val digested = sha.digest(bytes)
    UrlBase64.encodeBytes(digested)
  }


  /**
   * Deserializes the serialized data into usable keys.
   */
  def deserialize(serialized: String) = {
    if (serialized == null)
      throw new NullPointerException("serialized")

    val split = serialized.split(" ")
    if (split.length != 2)
      throw new RuntimeException("Serialized data is invalid")

    // Setup basic information
    val conf = BouncyCastleProvider.CONFIGURATION

    // Decode Q
    val decodedQ = UrlBase64.decodeToBytes(split(1))
    val point = EC_Params.getCurve.decodePoint(decodedQ)
    val pubParams = new ECPublicKeyParameters(point, EC_Params)
    val pubKey = new BCECPublicKey(EC_CURVE, pubParams, conf)

    // Decode D
    val D = new BigInteger(UrlBase64.decodeToBytes(split(0)))
    val privParams = new ECPrivateKeyParameters(D, EC_Params)
    val privKey = new BCECPrivateKey(EC_CURVE, privParams, pubKey, EC_ParamSpec, conf)

    LocalKeys(privKey, pubKey)
  }

  /**
   * Retrieves and deserializes local keys from the file system. If the file
   * doesn't exist then keys will be generated and stored at the designated
   * path.
   */
  def getFromFileSystem(file: File) = if (!file.exists) generate.having { keys =>
    Files.write(Paths.get(file.getPath), keys.serialize.getBytes("UTF-8"))
  }
  else {
    val bytes = Files.readAllBytes(Paths.get(file.getPath))
    val serialized = new String(bytes, "UTF-8")
    deserialize(serialized)
  }

  /**
   * Retrieves and deserializes local keys from a resource file.
   */
  def getFromClasspath(resource: String) = {
    val bytes = ByteStreams.toByteArray(getClass.getResourceAsStream(resource))
    val serialized = new String(bytes, "UTF-8")
    deserialize(serialized)
  }
}

