package com.tonihacks.doppler.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory TTL cache of `(project, config) → secrets`. Zero IntelliJ deps.
 *
 * Lazy expiry on read. Never persisted. After [invalidate] / [invalidateAll] the
 * map entry is removed and the secrets become eligible for GC, but any reference
 * already handed out by a prior [get] keeps the secrets reachable until the
 * caller releases it.
 */
class SecretCache(private val ttlMs: Long = DEFAULT_TTL_MS) {

    private data class Entry(val secrets: Map<String, String>, val expiresAt: Long) {
        // Override the data-class auto-generated toString so a stray log statement
        // (e.g. log.debug("cache state: $entry")) cannot dump secret values.
        override fun toString(): String = "Entry(secrets=[REDACTED x${secrets.size}], expiresAt=$expiresAt)"
    }

    // Pair key (not "$project/$config" concat) so a separator-bearing project name
    // can never alias another (project, config) pair. Pair.equals is structural on
    // both components.
    private val store = ConcurrentHashMap<Pair<String, String>, Entry>()

    fun get(project: String, config: String): Map<String, String>? {
        val k = project to config
        val entry = store[k] ?: return null
        return if (System.currentTimeMillis() >= entry.expiresAt) {
            // CAS-style remove (compare and swap): only delete if entry hasn't been replaced concurrently.
            store.remove(k, entry)
            null
        } else {
            entry.secrets
        }
    }

    /**
     * Stores [secrets] for ([project], [config]). The optional [ttlMs] parameter overrides
     * the constructor default — used by `DopplerProjectService` so a settings-page TTL
     * change takes effect on the next CLI fetch without rebuilding the cache.
     */
    fun put(
        project: String,
        config: String,
        secrets: Map<String, String>,
        ttlMs: Long = this.ttlMs,
    ) {
        store[project to config] = Entry(secrets, System.currentTimeMillis() + ttlMs)
    }

    fun invalidate(project: String, config: String) {
        store.remove(project to config)
    }

    fun invalidateAll() {
        store.clear()
    }

    companion object {
        const val DEFAULT_TTL_MS = 60_000L
    }
}
