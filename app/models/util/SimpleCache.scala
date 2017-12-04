package models.util

import com.google.common.cache.{ Cache => GuavaCache, CacheBuilder }
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.Callable

/**
 * Simple in-memory cache implementation, a wrapper around Guava cache.
 * Based on https://gist.github.com/brikis98/5842766
 */
class SimpleCache[K <: AnyRef, V <: Any](initialCapacity: Int, concurrencyLevel: Int,
                                         maximumSize: Option[Long]     = None,
                                         ttl:         Option[Duration] = None,
                                         recordStats: Boolean          = false) {

  // initialize guava cache
  private val cache: GuavaCache[K, V] = {
    def forSize(builder: CacheBuilder[_, _]) = maximumSize map builder.maximumSize getOrElse builder
    def forTtl(builder: CacheBuilder[_, _]) = ttl map { duration =>
      builder.expireAfterAccess(duration.toMillis, TimeUnit.MILLISECONDS)
    } getOrElse builder
    def forStats(builder: CacheBuilder[_, _]) = if (recordStats) builder.recordStats else builder

    val builder = (forSize _ andThen forTtl andThen forStats) {
      CacheBuilder.newBuilder.concurrencyLevel(concurrencyLevel).initialCapacity(initialCapacity)
    }.asInstanceOf[CacheBuilder[K, V]]

    builder.build[K, V]
  }

  /**
   * Optionally get the value associated with the given key.
   */
  def get(key: K): Option[V] = Option(cache.getIfPresent(key))

  /**
   * Get the value associated with the given key. If no value is already associated, then associate the given value
   * with the key and use it as the return value.
   *
   * Like Scala's ConcurrentMap, the value parameter will be lazily evaluated. However, unlike Scala's ConcurrentMap,
   * this method is a thread safe (atomic) operation.
   */
  def getOrElseUpdate(key: K, value: => V): V = cache.get(key, new Callable[V] {
    def call(): V = value
  })

  /**
   * Associate the given value with the given key
   */
  def put(key: K, value: V) = cache.put(key, value)

  /**
   * Remove the key and any associated value from the cache
   */
  def remove(key: K) = cache.invalidate(key)

  /**
   * Remove all keys and values from the cache
   */
  def clear() = cache.invalidateAll

  /**
   * Return how many items are in the cache
   */
  def size: Long = cache.size

  /**
   * Returns cache statistics.
   */
  def stats = cache.stats
}