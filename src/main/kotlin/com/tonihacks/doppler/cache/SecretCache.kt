package com.tonihacks.doppler.cache

import java.util.concurrent.ConcurrentHashMap

/** In-memory TTL cache of `(project, config) → secrets`. Lazy expiry on read. Never persisted. */
class SecretCache(private val ttlMs: Long = DEFAULT_TTL_MS) {

    private data class Entry(val secrets: Map<String, String>, val expiresAt: Long) {
        override fun toString(): String = "Entry(secrets=[REDACTED x${secrets.size}], expiresAt=$expiresAt)"
    }

    // Pair key (not "$project/$config" concat) so a separator-bearing project name
    // can't alias another (project, config) pair.
    private val store = ConcurrentHashMap<Pair<String, String>, Entry>()

    fun get(project: String, config: String): Map<String, String>? {
        val k = project to config
        val entry = store[k] ?: return null
        return if (System.currentTimeMillis() >= entry.expiresAt) {
            store.remove(k, entry)
            null
        } else {
            entry.secrets
        }
    }

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
