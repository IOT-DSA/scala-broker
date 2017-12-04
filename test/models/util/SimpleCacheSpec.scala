package models.util

import org.scalatestplus.play.PlaySpec

/**
 * Test suite for SimpleCache.
 */
class SimpleCacheSpec extends PlaySpec {

  val cache = new SimpleCache[String, Int](10, 1, None, None, true)

  "SimpleCache.get" should {
    "return None for missing key" in {
      cache.get("a") mustBe empty
      cache.stats.hitCount mustBe 0
      cache.stats.missCount mustBe 1
    }
    "return cached value for valid key" in {
      cache.put("b", 111)
      cache.get("b") mustBe Some(111)
      cache.stats.hitCount mustBe 1
    }
  }
  
  "SimpleCache.getOrElseUpdate" should {
    "insert value, if missing" in {
      cache.getOrElseUpdate("c", 222) mustBe 222
      cache.stats.missCount mustBe 2
      cache.get("c") mustBe Some(222)
      cache.stats.hitCount mustBe 2
    }
    "return stored value for valid key" in {
      cache.getOrElseUpdate("c", 333) mustBe 222
      cache.stats.hitCount mustBe 3
    }
  }
  
  "SimpleCache.remove" should {
    "remove valid key" in {
      cache.remove("b")
      cache.get("b") mustBe empty
    }
  }
  
  "SimpleCache.clear" should {
    "remove all keys" in {
      cache.clear
      cache.size mustBe 0
    }
  }
}