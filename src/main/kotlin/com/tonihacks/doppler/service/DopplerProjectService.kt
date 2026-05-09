package com.tonihacks.doppler.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.tonihacks.doppler.cache.SecretCache
import com.tonihacks.doppler.cli.DopplerCliClient
import com.tonihacks.doppler.cli.DopplerResult
import com.tonihacks.doppler.settings.DopplerSettingsState
import org.jetbrains.annotations.TestOnly

/**
 * Single source of truth for "what secrets does this IDE project use?". Wires
 * [DopplerSettingsState], [SecretCache], and [DopplerCliClient].
 *
 * **Threading:** every public method shells out via [DopplerCliClient] and therefore
 * **must be called off the EDT**. Marked with
 * `@RequiresBackgroundThread` so the platform's threading inspection flags EDT callers.
 *
 * The [cliFactory] indirection lets tests inject a mock; in production the default
 * builds a fresh client from the current settings on every call (cheap — `DopplerCliClient`
 * is stateless), so a settings-page CLI-path change takes effect immediately. Caching
 * the client would create a stale-config-injection risk on settings change.
 */
@Service(Service.Level.PROJECT)
class DopplerProjectService(private val project: Project) {

    // Overridden by the @TestOnly secondary constructor so tests can inject a fake CLI
    // without touching the production code path.
    private var cliFactory: () -> DopplerCliClient = { defaultCli(project) }

    @TestOnly
    constructor(project: Project, cliFactory: () -> DopplerCliClient) : this(project) {
        this.cliFactory = cliFactory
    }

    private val cache = SecretCache()

    /**
     * Returns merged secrets for injection. Empty map when the plugin is disabled
     * or not fully configured (project / config slug blank).
     *
     * On CLI failure throws [DopplerFetchException] with the CLI's stderr verbatim —
     * never silently empty.
     *
     * **Caller contract:** never log the returned map, never put it in a notification
     * body, never persist it. The map's `toString()` is wrapped to redact values as a
     * defense against the most common "$secrets" log mistake, but the contents of
     * `entries` / `values` are still raw and a determined-careless caller can leak them.
     * Inject into process env, then drop the reference.
     *
     * **TTL note:** the [DopplerSettingsState.State.cacheTtlSeconds] read here is applied
     * on the *next* CLI fetch (i.e. on cache miss). Already-cached entries keep their
     * original `expiresAt` until expiry; if the user shortens TTL and wants it to take
     * effect immediately, [invalidateCache] is the explicit lever.
     */
    @RequiresBackgroundThread
    fun fetchSecrets(): Map<String, String> {
        val s = DopplerSettingsState.getInstance(project).state
        if (!s.enabled || s.dopplerProject.isBlank() || s.dopplerConfig.isBlank()) return emptyMap()
        val cached = cache.get(s.dopplerProject, s.dopplerConfig)
        return (cached ?: fetchAndCache(s)).redactedView()
    }

    private fun fetchAndCache(s: DopplerSettingsState.State): Map<String, String> =
        when (val r = cliFactory().downloadSecrets(s.dopplerProject, s.dopplerConfig)) {
            is DopplerResult.Success -> r.value.also {
                cache.put(s.dopplerProject, s.dopplerConfig, it, ttlMs = s.cacheTtlSeconds * 1000L)
            }
            is DopplerResult.Failure -> throw DopplerFetchException(r.error)
        }

    fun invalidateCache() = cache.invalidateAll()

    @RequiresBackgroundThread
    fun isCliAvailable(): Boolean = cliFactory().version() is DopplerResult.Success

    @RequiresBackgroundThread
    fun isAuthenticated(): Boolean = cliFactory().me() is DopplerResult.Success

    companion object {
        fun getInstance(project: Project): DopplerProjectService = project.service()

        private fun defaultCli(project: Project): DopplerCliClient {
            val cliPath = DopplerSettingsState.getInstance(project).state.cliPath
            return DopplerCliClient(cliPath = cliPath.takeIf { it.isNotBlank() })
        }

        // Wraps the inner secret map so a stray `log.debug("env: $secrets")` prints a
        // redacted summary instead of `{API_KEY=value, ...}`. Caveat: `entries`, `keys`,
        // `values` still proxy to the inner map — a caller that explicitly logs those
        // collections still leaks. Same precedent as SecretCache.Entry.toString.
        private fun Map<String, String>.redactedView(): Map<String, String> =
            object : Map<String, String> by this {
                override fun toString(): String = "[REDACTED x${this@redactedView.size}]"
            }
    }
}
