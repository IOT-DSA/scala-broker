package models.util

import akka.actor.TypedActor
import models.akka.AbstractActorSpec
import models.api.DSANode
import org.bouncycastle.jcajce.provider.digest.SHA256
import org.scalatest.Inspectors

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Tokens test suite.
  */
class TokensSpec extends AbstractActorSpec with Inspectors {

  val beValidTokenChar = (be >= 0x30 and be <= 0x39) or (be >= 0x41 and be <= 0x5A) or (be >= 0x61 and be <= 0x7A)

  val sha = new SHA256.Digest

  val extension = TypedActor(system)

  "createToken" should {
    val token = Tokens.createToken()
    "produce a 48-byte long token" in {
      token.length mustBe 48
    }
    "produce a token from the valid alphabet" in {
      forAll(token)(_.toInt must beValidTokenChar)
    }
  }

  "regenerate" should {
    val token = Tokens.createToken()
    val newToken = Tokens.regenerate(token)
    "use the first 16 characters of the token" in {
      newToken.take(16) mustBe token.take(16)
    }
    "not be identifal to the old token" in {
      token must not be (newToken)
    }
  }

  "makeTokenForNode" should {
    "generate unique token for a node" in {
      val node: DSANode = extension.typedActorOf(DSANode.props(None), "foo")
      val fTokenAndChildren = for {
        token <- Tokens.makeTokenForNode(node)
        children <- node.children
      } yield (token, children)
      whenReady(fTokenAndChildren) {
        case (token, children) => children.keys must not contain token
      }
    }
  }

  "hashToken" should {
    "produce a valid combination of token and (token+dsId) digest" in {
      val token = "0123456789ABCDEF0123456789ABCDEF"
      val dsId = "abc"
      val hash = Tokens.hashToken(token, dsId)
      hash.take(16) mustBe token.take(16)
      hash.drop(16) mustBe UrlBase64.encodeBytes(sha.digest((dsId + token).getBytes))
    }
  }
}