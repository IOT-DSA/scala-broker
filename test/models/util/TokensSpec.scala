package models.util

import org.scalatestplus.play.PlaySpec

/**
  * Specs for tokens
  */
class TokensSpec extends PlaySpec {

  "Generated token" should {
    val token48 = Tokens.createToken()
    "have 48 bit length" in {
      token48.length mustBe 48
    }
  }
}
