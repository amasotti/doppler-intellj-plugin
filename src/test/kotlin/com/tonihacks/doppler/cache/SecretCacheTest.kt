package com.tonihacks.doppler.cache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SecretCacheTest {

    @Test
    fun `get returns null when cache is empty`() {
        val cache = SecretCache(ttlMs = 60_000)
        assertThat(cache.get("p", "c")).isNull()
    }

    @Test
    fun `get returns cached value within ttl`() {
        val cache = SecretCache(ttlMs = 60_000)
        cache.put("proj", "dev", mapOf("KEY" to "value"))
        assertThat(cache.get("proj", "dev"))
            .containsExactlyEntriesOf(mapOf("KEY" to "value"))
    }

    @Test
    fun `get returns null after ttl expires`() {
        // 10x margin keeps the test stable on slow CI runners (cold JVM, GC pause).
        val cache = SecretCache(ttlMs = 50)
        cache.put("proj", "dev", mapOf("KEY" to "value"))
        Thread.sleep(500)
        assertThat(cache.get("proj", "dev")).isNull()
    }

    @Test
    fun `invalidate removes only the targeted entry`() {
        val cache = SecretCache(ttlMs = 60_000)
        cache.put("p1", "c1", mapOf("A" to "1"))
        cache.put("p2", "c2", mapOf("B" to "2"))
        cache.invalidate("p1", "c1")
        assertThat(cache.get("p1", "c1")).isNull()
        assertThat(cache.get("p2", "c2")).containsEntry("B", "2")
    }

    @Test
    fun `invalidateAll removes every entry`() {
        val cache = SecretCache(ttlMs = 60_000)
        cache.put("p1", "c1", mapOf("A" to "1"))
        cache.put("p2", "c2", mapOf("B" to "2"))
        cache.invalidateAll()
        assertThat(cache.get("p1", "c1")).isNull()
        assertThat(cache.get("p2", "c2")).isNull()
    }

    @Test
    fun `put with explicit ttlMs overrides constructor default`() {
        // Used by DopplerProjectService to honor settings-page TTL changes per fetch.
        val cache = SecretCache(ttlMs = 60_000)
        cache.put("p", "c", mapOf("K" to "v"), ttlMs = 50)
        Thread.sleep(500)
        assertThat(cache.get("p", "c")).isNull()
    }

    @Test
    fun `put overwrites existing entry and resets ttl`() {
        val cache = SecretCache(ttlMs = 60_000)
        cache.put("p", "c", mapOf("K" to "old"))
        cache.put("p", "c", mapOf("K" to "new"))
        assertThat(cache.get("p", "c")).containsExactlyEntriesOf(mapOf("K" to "new"))
    }

    @Test
    fun `entries with similar key components do not collide`() {
        // Pair<String,String> key prevents the "a/b" + "c" vs "a" + "b/c" string-concat collision.
        val cache = SecretCache(ttlMs = 60_000)
        cache.put("a/b", "c", mapOf("X" to "1"))
        cache.put("a", "b/c", mapOf("X" to "2"))
        assertThat(cache.get("a/b", "c")).containsEntry("X", "1")
        assertThat(cache.get("a", "b/c")).containsEntry("X", "2")
    }

    @Test
    fun `concurrent put and get does not deadlock or throw`() {
        val cache = SecretCache(ttlMs = 60_000)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (1..16).map { i ->
            Thread {
                try {
                    repeat(200) {
                        cache.put("p$i", "c", mapOf("K" to "$i-$it"))
                        cache.get("p$i", "c")
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }
        threads.forEach { check(!it.isAlive) { "thread did not finish in 5s" } }
        assertThat(errors).isEmpty()
        assertThat(cache.get("p1", "c")).isNotNull()
    }
}
