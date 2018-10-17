package models.util

import models.api.DSANode
import org.bouncycastle.jcajce.provider.digest.SHA256
import play.api.Logger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random

/**
  * Provides operations over security tokens.
  */
object Tokens {

  protected val log = Logger(getClass)

  /**
    * Extracts the first 16 digits of the token.
    *
    * @param token
    * @return
    */
  def getTokenId(token: String) = token.take(16)

  /**
    * Creates a unique token for the node.
    *
    * @param dsaNode
    * @return
    */
  def makeTokenForNode(dsaNode: DSANode): Future[String] = {
    val token = createToken()
    val tokenId = getTokenId(token)

    dsaNode.child(tokenId).flatMap {
      case None => Future(token)
      case _    => makeTokenForNode(dsaNode)
    }
  }

  /**
    * Generates a new token by combining the first 16 digits of the current one and the tail of the newly generated token.
    *
    * @param curToken
    * @return
    */
  def regenerate(curToken: String): String = getTokenId(curToken) + createToken().drop(16)

  /**
    * Creates a new random token.
    */
  //TODO: this is not Scala, needs to be rewritten
  def createToken(): String = {
    val tokenCodes = Array.ofDim[Byte](48)
    var i = 0;
    while (i < 48) {
      val n = Random.nextInt(Byte.MaxValue)
      if ((n >= 0x30 && n <= 0x39) ||
        (n >= 0x41 && n <= 0x5A) ||
        (n >= 0x61 && n <= 0x7A)) {
        tokenCodes(i) = n.toByte
        i += 1
      }
    }
    new String(tokenCodes, "UTF-8")
  }

  /**
    * Creates a token hash by combining the first 16 digits of the token and the dsId+token digest.
    *
    * @param token
    * @param dsId
    * @return
    */
  def hashToken(token: String, dsId: String): String = {
    val sha = new SHA256.Digest
    val digested = sha.digest((dsId + token).getBytes)

    val hashedToken = token.take(16) + UrlBase64.encodeBytes(digested)

    hashedToken
  }
}