package models.util

import akka.actor.ActorPath
import org.scalatestplus.play.PlaySpec

class DsaToAkkaCoderSpec extends PlaySpec {
  import DsaToAkkaCoder._

  val dsaPaths = Seq("Normal", "With space", "long/path/with spaces and '@=~'")

  "dsaToAkkaPath" should {
    "return path of Valid Path Elements" in {
      assert(dsaPaths.flatMap(_.forAkka.split(PATH_SEP)).forall(
        ActorPath.isValidPathElement)
      )
    }
  }

  "dsa to akka and back" should {
    "give the same string" in {
      assert(dsaPaths.forall( p => p == p.forAkka.forDsa))
    }
  }
}
