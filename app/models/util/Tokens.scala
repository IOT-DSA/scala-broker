package models.util

import models.api.DSANode

import scala.concurrent.Future
import scala.util.Random
import scala.concurrent.ExecutionContext.Implicits.global
import org.bouncycastle.jcajce.provider.digest.SHA256
import play.api.Logger

object Tokens {
  protected val log = Logger(getClass)

  def getTokenId(token: String) = {
    if (token.length < 16) {
      log.error("Token length is less then 16, tokenId has been set empty")
      ""
    } else
      token.substring(0, 16)
  }

  def makeToken(dsaNode: DSANode, curToken: Option[String] = None) : Future[String] = {
    val token = createToken()
    val tokenId = getTokenId(token)

    val r = dsaNode.child(tokenId).flatMap {
        case None => Future(token)
        case _ => makeToken(dsaNode)
    }

    r
  }

  def regenerate(curToken: String) : String = {
    val newToken = createToken()

    val token = getTokenId(curToken) + newToken.substring(16)

    token
  }

  def createToken(): String = {
    val tokenCodes = Array.ofDim[Byte](48)
    var i = 0;
    while (i < 48) {
      val n = Random.nextInt(Byte.MaxValue)
      if ((n >= 0x30 && n <= 0x39) ||
        (n >= 0x41 && n <= 0x5A) ||
        (n >= 0x61 && n <= 0x7A)) {
        tokenCodes(i) = n.toByte;
        i+=1
      }
    }
    val rslt = new String(tokenCodes, "UTF-8")

    rslt
  }

  def hashToken(token: String, dsId: String): String = {
    val sha = new SHA256.Digest
    val digested = sha.digest((dsId+token).getBytes)

    val hashedToken = token.substring(0,16) + UrlBase64.encodeBytes(digested)

    hashedToken
  }

}
