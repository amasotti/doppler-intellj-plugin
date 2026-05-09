package com.tonihacks.doppler.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.tonihacks.doppler.cache.SecretCache
import com.tonihacks.doppler.cli.DopplerCliClient
import com.tonihacks.doppler.cli.DopplerResult
import com.tonihacks.doppler.settings.DopplerSettingsState

/**
 * Surfaces a CLI failure to the caller. Injectors / UI translate this into a notification
 * and abort the run launch.
 *
 * The message is the CLI's stderr verbatim — it must not contain secret values
 * (see `DopplerCliClient` contract).
 */
class DopplerFetchException(message: String) : RuntimeException(message)

/**
 * Single source of truth for "what secrets does this IDE project use?". Wires
 * [DopplerSettingsState], [SecretCache], and [DopplerCliClient].
 *
 * The [cliFactory] indirection lets tests inject a mock; in production the default
 * builds a fresh client from the current settings each call (cheap — `DopplerCliClient`
 * is stateless), so a settings-page CLI-path change takes effect immediately.
 */
@Service(Service.Level.PROJECT)
class DopplerProjectService(
    private val project: Project,
    private val cliFactory: () -> DopplerCliClient = { defaultCli(project) },
) {

    private val cache = SecretCache()

    /**
     * Returns merged secrets for injection. Empty map when the plugin is disabled.
     * Throws [DopplerFetchException] on CLI failure — never silently empty.
     */
    fun fetchSecrets(): Map<String, String> {
        val s = DopplerSettingsState.getInstance(project).state
        if (!s.enabled) return emptyMap()
        return cache.get(s.dopplerProject, s.dopplerConfig) ?: fetchAndCache(s)
    }

    private fun fetchAndCache(s: DopplerSettingsState.State): Map<String, String> =
        when (val r = cliFactory().downloadSecrets(s.dopplerProject, s.dopplerConfig)) {
            is DopplerResult.Success -> r.value.also {
                cache.put(s.dopplerProject, s.dopplerConfig, it, ttlMs = s.cacheTtlSeconds * 1000L)
            }
            is DopplerResult.Failure -> throw DopplerFetchException(r.error)
        }

    fun invalidateCache() = cache.invalidateAll()

    fun isCliAvailable(): Boolean = cliFactory().version() is DopplerResult.Success

    fun isAuthenticated(): Boolean = cliFactory().me() is DopplerResult.Success

    companion object {
        fun getInstance(project: Project): DopplerProjectService = project.service()

        private fun defaultCli(project: Project): DopplerCliClient {
            val cliPath = DopplerSettingsState.getInstance(project).state.cliPath
            return DopplerCliClient(cliPath = cliPath.takeIf { it.isNotBlank() })
        }
    }
}
