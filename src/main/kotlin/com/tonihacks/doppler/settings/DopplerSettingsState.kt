package com.tonihacks.doppler.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Per-IDE-project Doppler settings persisted to `.idea/doppler.xml`.
 *
 * **No secret values are stored here** — only project / config slugs and config knobs.
 * The XML file is treated as committed-and-public per spec §7.1.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "DopplerSettings",
    storages = [Storage("doppler.xml")],
)
class DopplerSettingsState : PersistentStateComponent<DopplerSettingsState.State> {

    data class State(
        var enabled: Boolean = true,
        var dopplerProject: String = "",
        var dopplerConfig: String = "",
        var cacheTtlSeconds: Int = DEFAULT_CACHE_TTL_SECONDS,
        var cliPath: String = "",
    ) {
        // Override the data-class auto-generated toString so a stray log statement cannot
        // leak filesystem paths (cliPath) or internal naming (project / config slugs).
        // Defense in depth — none of these are secrets, but they shouldn't
        // surface in logs either. Same precedent as SecretCache.Entry.
        override fun toString(): String =
            "State(enabled=$enabled, dopplerProject=<redacted>, dopplerConfig=<redacted>, " +
                "cacheTtlSeconds=$cacheTtlSeconds, cliPath=<redacted>)"
    }

    private var stateInstance: State = State()

    // Kotlin callers reach this via the synthetic `state` property accessor
    // (JavaBean style: `getState()` ⇒ `settings.state`).
    override fun getState(): State = stateInstance

    override fun loadState(state: State) {
        stateInstance = state
    }

    val isConfigured: Boolean
        get() = with(stateInstance) {
            enabled && dopplerProject.isNotBlank() && dopplerConfig.isNotBlank()
        }

    companion object {
        const val DEFAULT_CACHE_TTL_SECONDS = 60

        fun getInstance(project: Project): DopplerSettingsState = project.service()
    }
}
