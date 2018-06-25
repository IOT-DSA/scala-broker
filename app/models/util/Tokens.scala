package models.util

import models.api.DSANode

import scala.concurrent.Future
import scala.util.Random

import scala.concurrent.ExecutionContext.Implicits.global

object Tokens {
  def makeToken(dsaNode: DSANode) : Future[String] = {
    val token = createToken()
    val tokenId = token.substring(0, 16);

    val r = dsaNode.child(tokenId).flatMap {
        case None => Future(token)
        case _ => makeToken(dsaNode)
    }
    r
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

}
