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

/** Single source of truth for "what secrets does this IDE project use?". */
@Service(Service.Level.PROJECT)
class DopplerProjectService(private val project: Project) {

    // Built fresh on every call so a settings-page CLI-path change takes effect immediately.
    private var cliFactory: () -> DopplerCliClient = { defaultCli(project) }

    @TestOnly
    constructor(project: Project, cliFactory: () -> DopplerCliClient) : this(project) {
        this.cliFactory = cliFactory
    }

    private val cache = SecretCache()

    /**
     * Returns merged secrets for injection. Empty map when disabled or unconfigured.
     * Throws [DopplerFetchException] with CLI stderr verbatim on failure — never silently empty.
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

        // Redacts toString so `log.debug("env: $secrets")` cannot leak values.
        // Callers that explicitly log .entries / .values still bypass this.
        private fun Map<String, String>.redactedView(): Map<String, String> =
            object : Map<String, String> by this {
                override fun toString(): String = "[REDACTED x${this@redactedView.size}]"
            }
    }
}
