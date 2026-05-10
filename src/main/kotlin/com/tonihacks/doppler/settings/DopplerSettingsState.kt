package com.tonihacks.doppler.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Per-IDE-project Doppler settings persisted to `.idea/doppler.xml`. No secret values stored. */
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
        // Defense-in-depth: nothing here is a secret value, but slugs and the CLI
        // path shouldn't surface in logs either.
        override fun toString(): String =
            "State(enabled=$enabled, dopplerProject=<redacted>, dopplerConfig=<redacted>, " +
                "cacheTtlSeconds=$cacheTtlSeconds, cliPath=<redacted>)"
    }

    private var stateInstance: State = State()

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
